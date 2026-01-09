package com.payments.platform.payments.service

import com.payments.platform.payments.domain.Chargeback
import com.payments.platform.payments.domain.ChargebackOutcome
import com.payments.platform.payments.domain.ChargebackState
import com.payments.platform.payments.domain.ChargebackStateMachine
import com.payments.platform.payments.persistence.ChargebackEntity
import com.payments.platform.payments.persistence.ChargebackRepository
import com.payments.platform.payments.persistence.PaymentRepository
import com.stripe.model.Dispute
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Chargeback orchestration service.
 * 
 * Key principle: Ledger writes ONLY after Stripe confirms money movement.
 * - Chargeback creation: Write to ledger immediately (money is debited immediately)
 * - Stripe webhook confirms chargeback won: THEN update ledger (money returned)
 * - Stripe webhook confirms chargeback lost: THEN update ledger (finalize loss)
 * 
 * This ensures: "Ledger only records actual money movement"
 */
@Service
class ChargebackService(
    private val chargebackRepository: ChargebackRepository,
    private val paymentRepository: PaymentRepository,
    private val stateMachine: ChargebackStateMachine
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    /**
     * Creates a chargeback record from a Stripe Dispute.
     * 
     * This is called when charge.dispute.created webhook is received.
     * Money is immediately debited from STRIPE_CLEARING, so we write to ledger immediately.
     * 
     * @param stripeDispute Stripe Dispute object
     * @param paymentId Payment ID (looked up from Stripe Charge ID)
     * @return Created chargeback
     */
    @Transactional
    fun createChargebackFromDispute(stripeDispute: Dispute, paymentId: UUID): Chargeback {
        // Check idempotency (if chargeback already exists, return it)
        val existingChargeback = chargebackRepository.findByStripeDisputeId(stripeDispute.id)
        if (existingChargeback != null) {
            logger.info("Chargeback with Stripe Dispute ID ${stripeDispute.id} already exists, returning existing chargeback")
            return existingChargeback.toDomain()
        }
        
        // Verify payment exists
        val paymentEntity = paymentRepository.findById(paymentId).orElseThrow {
            ChargebackCreationException("Payment not found: $paymentId")
        }
        val payment = paymentEntity.toDomain()
        
        // Generate chargeback ID and idempotency key
        val chargebackId = UUID.randomUUID()
        val idempotencyKey = "chargeback_${chargebackId}_${System.currentTimeMillis()}"
        
        // Check idempotency by key
        val existingByKey = chargebackRepository.findByIdempotencyKey(idempotencyKey)
        if (existingByKey != null) {
            logger.info("Chargeback with idempotency key $idempotencyKey already exists, returning existing chargeback")
            return existingByKey.toDomain()
        }
        
        // Extract dispute details
        val chargebackAmountCents = stripeDispute.amount
        val disputeFeeCents = stripeDispute.balanceTransactions?.firstOrNull()?.fee ?: 0L
        val currency = stripeDispute.currency.uppercase()
        val stripeChargeId = stripeDispute.charge
        val reason = stripeDispute.reason
        val evidenceDueBy = stripeDispute.evidenceDetails?.dueBy?.let { Instant.ofEpochSecond(it) }
        
        // Determine initial state based on Stripe dispute status
        val initialState = when (stripeDispute.status) {
            "warning_needs_response" -> ChargebackState.DISPUTE_CREATED
            "needs_response" -> ChargebackState.NEEDS_RESPONSE
            "under_review" -> ChargebackState.UNDER_REVIEW
            else -> ChargebackState.DISPUTE_CREATED
        }
        
        // Create chargeback (ledger_transaction_id will be set after ledger write)
        val chargeback = Chargeback(
            id = chargebackId,
            paymentId = paymentId,
            chargebackAmountCents = chargebackAmountCents,
            disputeFeeCents = disputeFeeCents,
            currency = currency,
            state = initialState,
            stripeDisputeId = stripeDispute.id,
            stripeChargeId = stripeChargeId,
            reason = reason,
            evidenceDueBy = evidenceDueBy,
            ledgerTransactionId = null,  // Will be set after ledger write
            idempotencyKey = idempotencyKey,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            closedAt = null,
            outcome = null
        )
        
        val entity = ChargebackEntity.fromDomain(chargeback)
        val saved = chargebackRepository.save(entity)
        
        logger.info(
            "Created chargeback ${chargeback.id} for payment $paymentId " +
            "(Stripe Dispute ID: ${stripeDispute.id}, amount: $chargebackAmountCents $currency, " +
            "dispute fee: $disputeFeeCents $currency, state: $initialState)"
        )
        
        return saved.toDomain()
    }
    
    /**
     * Transitions a chargeback to a new state.
     * 
     * @param chargebackId Chargeback ID
     * @param newState New state
     */
    @Transactional
    fun transitionChargeback(chargebackId: UUID, newState: ChargebackState) {
        val chargebackEntity = chargebackRepository.findById(chargebackId).orElseThrow {
            IllegalArgumentException("Chargeback not found: $chargebackId")
        }
        
        val currentState = chargebackEntity.state
        
        // Validate state transition using state machine
        try {
            stateMachine.transition(currentState, newState)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid chargeback state transition: $currentState → $newState. " +
                "Allowed transitions from $currentState: ${stateMachine.getValidNextStates(currentState)}",
                e
            )
        }
        
        chargebackEntity.state = newState
        chargebackEntity.updatedAt = Instant.now()
        
        // If transitioning to terminal state, set closedAt and outcome
        if (newState == ChargebackState.WON || newState == ChargebackState.LOST || 
            newState == ChargebackState.WITHDRAWN || newState == ChargebackState.WARNING_CLOSED) {
            chargebackEntity.closedAt = Instant.now()
            chargebackEntity.outcome = when (newState) {
                ChargebackState.WON -> ChargebackOutcome.WON
                ChargebackState.LOST -> ChargebackOutcome.LOST
                ChargebackState.WITHDRAWN -> ChargebackOutcome.WITHDRAWN
                ChargebackState.WARNING_CLOSED -> ChargebackOutcome.WARNING_CLOSED
                else -> null
            }
        }
        
        chargebackRepository.save(chargebackEntity)
        
        logger.info("Chargeback $chargebackId transitioned from $currentState to $newState")
    }
    
    /**
     * Updates chargeback with ledger transaction ID after ledger write.
     * 
     * @param chargebackId Chargeback ID
     * @param ledgerTransactionId Ledger transaction ID
     */
    @Transactional
    fun updateLedgerTransactionId(chargebackId: UUID, ledgerTransactionId: UUID) {
        val chargebackEntity = chargebackRepository.findById(chargebackId).orElseThrow {
            IllegalArgumentException("Chargeback not found: $chargebackId")
        }
        
        chargebackEntity.ledgerTransactionId = ledgerTransactionId
        chargebackEntity.updatedAt = Instant.now()
        chargebackRepository.save(chargebackEntity)
        
        logger.info("Updated chargeback $chargebackId with ledger transaction ID: $ledgerTransactionId")
    }
    
    /**
     * Gets a chargeback by ID.
     * 
     * @param chargebackId Chargeback ID
     * @return Chargeback
     */
    fun getChargeback(chargebackId: UUID): Chargeback {
        val chargebackEntity = chargebackRepository.findById(chargebackId).orElseThrow {
            IllegalArgumentException("Chargeback not found: $chargebackId")
        }
        return chargebackEntity.toDomain()
    }
    
    /**
     * Gets a chargeback by Stripe Dispute ID.
     * 
     * @param stripeDisputeId Stripe Dispute ID
     * @return Chargeback, or null if not found
     */
    fun getChargebackByStripeDisputeId(stripeDisputeId: String): Chargeback? {
        return chargebackRepository.findByStripeDisputeId(stripeDisputeId)?.toDomain()
    }
    
    /**
     * Gets all chargebacks for a payment.
     * 
     * @param paymentId Payment ID
     * @return List of chargebacks
     */
    fun getChargebacksByPaymentId(paymentId: UUID): List<Chargeback> {
        return chargebackRepository.findByPaymentId(paymentId).map { it.toDomain() }
    }
}

/**
 * Exception thrown when chargeback creation fails.
 */
class ChargebackCreationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

