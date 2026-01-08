package com.payments.platform.payments.service

import com.payments.platform.payments.domain.PaymentState
import com.payments.platform.payments.domain.Refund
import com.payments.platform.payments.domain.RefundState
import com.payments.platform.payments.persistence.PaymentRepository
import com.payments.platform.payments.persistence.RefundEntity
import com.payments.platform.payments.persistence.RefundRepository
import com.payments.platform.payments.stripe.StripeService
import com.payments.platform.payments.stripe.StripeServiceException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Refund orchestration service.
 * 
 * Key principle: Ledger writes ONLY after Stripe confirms refund completion.
 * - Refund creation: NO ledger write (no money has moved yet)
 * - Stripe webhook confirms refund: THEN write to ledger
 * - This ensures: "Ledger only records actual money movement"
 */
@Service
class RefundService(
    private val refundRepository: RefundRepository,
    private val paymentRepository: PaymentRepository,
    private val stripeService: StripeService,
    private val paymentService: PaymentService
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    /**
     * Creates a full refund for a payment.
     * 
     * Order of operations:
     * 1. Validate payment exists and is CAPTURED
     * 2. Validate payment hasn't been refunded already
     * 3. Generate refund IDs and idempotency key
     * 4. Calculate refund amounts (full refund = original amounts)
     * 5. Create Stripe Refund
     * 6. Persist refund (state = REFUNDING, ledger_transaction_id = NULL)
     * 7. Return refund
     * 
     * NO ledger write at this stage - money hasn't moved yet.
     * 
     * @param paymentId Payment ID to refund
     * @return Created refund
     * @throws RefundCreationException if refund creation fails
     */
    @Transactional
    fun createRefund(paymentId: UUID): Refund {
        // Find payment
        val paymentEntity = paymentRepository.findById(paymentId).orElseThrow {
            RefundCreationException("Payment not found: $paymentId")
        }
        val payment = paymentEntity.toDomain()
        
        // Validate payment state
        if (payment.state != PaymentState.CAPTURED) {
            throw RefundCreationException(
                "Payment $paymentId cannot be refunded. Current state: ${payment.state}. " +
                "Only CAPTURED payments can be refunded."
            )
        }
        
        // Validate payment hasn't been refunded already
        val existingRefunds = refundRepository.findByPaymentId(paymentId)
        if (existingRefunds.isNotEmpty()) {
            throw RefundCreationException(
                "Payment $paymentId has already been refunded. " +
                "Only one refund per payment is currently supported."
            )
        }
        
        // Validate payment has Stripe PaymentIntent ID
        val stripePaymentIntentId = payment.stripePaymentIntentId
            ?: throw RefundCreationException("Payment $paymentId has no Stripe PaymentIntent ID")
        
        // Generate refund ID and idempotency key
        val refundId = UUID.randomUUID()
        val idempotencyKey = "refund_${refundId}_${System.currentTimeMillis()}"
        
        // Check idempotency (if refund already exists, return it)
        val existingRefund = refundRepository.findByIdempotencyKey(idempotencyKey)
        if (existingRefund != null) {
            logger.info("Refund with idempotency key $idempotencyKey already exists, returning existing refund")
            return existingRefund.toDomain()
        }
        
        // Calculate refund amounts (full refund = original amounts)
        val refundAmountCents = payment.grossAmountCents
        val platformFeeRefundCents = payment.platformFeeCents
        val netSellerRefundCents = payment.netSellerAmountCents
        
        // Validate refund calculation
        require(refundAmountCents == platformFeeRefundCents + netSellerRefundCents) {
            "Refund calculation error: refund_amount != platform_fee_refund + net_seller_refund"
        }
        
        // Create Stripe Refund (full refund)
        val stripeRefund = try {
            stripeService.createRefund(
                paymentIntentId = stripePaymentIntentId,
                amountCents = null  // null = full refund
            )
        } catch (e: StripeServiceException) {
            throw RefundCreationException("Failed to create Stripe Refund: ${e.message}", e)
        }
        
        // Persist refund with Stripe Refund ID (NO ledger write - money hasn't moved yet)
        val refund = Refund(
            id = refundId,
            paymentId = paymentId,
            refundAmountCents = refundAmountCents,
            platformFeeRefundCents = platformFeeRefundCents,
            netSellerRefundCents = netSellerRefundCents,
            currency = payment.currency,
            state = RefundState.REFUNDING,
            stripeRefundId = stripeRefund.id,
            ledgerTransactionId = null,  // NULL until refund confirmed (ledger write happens after Stripe confirms)
            idempotencyKey = idempotencyKey,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val entity = RefundEntity.fromDomain(refund)
        val saved = refundRepository.save(entity)
        
        logger.info(
            "Created refund ${refund.id} for payment $paymentId " +
            "(Stripe Refund ID: ${stripeRefund.id}, amount: $refundAmountCents ${payment.currency})"
        )
        
        return saved.toDomain()
    }
    
    /**
     * Transitions a refund to a new state.
     * 
     * @param refundId Refund ID
     * @param newState New state
     */
    @Transactional
    fun transitionRefund(refundId: UUID, newState: RefundState) {
        val refundEntity = refundRepository.findById(refundId).orElseThrow {
            IllegalArgumentException("Refund not found: $refundId")
        }
        
        val currentState = refundEntity.state
        
        // Validate state transition
        val validTransitions = mapOf(
            RefundState.REFUNDING to setOf(RefundState.REFUNDED, RefundState.FAILED),
            RefundState.REFUNDED to emptySet<RefundState>(),  // Terminal state
            RefundState.FAILED to emptySet<RefundState>()     // Terminal state
        )
        
        if (!validTransitions[currentState]?.contains(newState)!!) {
            throw IllegalArgumentException(
                "Invalid refund state transition: $currentState → $newState. " +
                "Allowed transitions from $currentState: ${validTransitions[currentState]}"
            )
        }
        
        refundEntity.state = newState
        refundEntity.updatedAt = Instant.now()
        refundRepository.save(refundEntity)
        
        logger.info("Refund $refundId transitioned from $currentState to $newState")
    }
    
    /**
     * Gets a refund by ID.
     * 
     * @param refundId Refund ID
     * @return Refund
     */
    fun getRefund(refundId: UUID): Refund {
        val refundEntity = refundRepository.findById(refundId).orElseThrow {
            IllegalArgumentException("Refund not found: $refundId")
        }
        return refundEntity.toDomain()
    }
    
    /**
     * Gets all refunds for a payment.
     * 
     * @param paymentId Payment ID
     * @return List of refunds
     */
    fun getRefundsByPaymentId(paymentId: UUID): List<Refund> {
        return refundRepository.findByPaymentId(paymentId).map { it.toDomain() }
    }
}

/**
 * Exception thrown when refund creation fails.
 */
class RefundCreationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

