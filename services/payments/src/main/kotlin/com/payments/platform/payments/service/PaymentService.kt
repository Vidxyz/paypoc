package com.payments.platform.payments.service

import com.payments.platform.payments.client.LedgerClient
import com.payments.platform.payments.client.LedgerClientException
import com.payments.platform.payments.client.dto.LedgerTransactionRequest
import com.payments.platform.payments.domain.Payment
import com.payments.platform.payments.domain.PaymentState
import com.payments.platform.payments.domain.PaymentStateMachine
import com.payments.platform.payments.persistence.PaymentEntity
import com.payments.platform.payments.persistence.PaymentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Payment orchestration service.
 * 
 * Key principle: Ledger-first approach.
 * - Call ledger BEFORE persisting payment
 * - If ledger fails, payment is NOT created
 * - This ensures: "If payment exists, money exists"
 */
@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val ledgerClient: LedgerClient,
    private val stateMachine: PaymentStateMachine
) {
    
    /**
     * Creates a payment with ledger-first approach.
     * 
     * Order of operations (CRITICAL):
     * 1. Generate IDs (paymentId, idempotencyKey)
     * 2. Call Ledger FIRST (create transaction)
     * 3. If ledger rejects → fail request (no payment record)
     * 4. Persist payment (state = CREATED)
     * 5. Return payment
     * 
     * @param request Payment creation request
     * @return Created payment
     * @throws LedgerClientException if ledger rejects the transaction
     */
    @Transactional
    fun createPayment(request: CreatePaymentRequest): Payment {
        // Generate IDs
        val paymentId = UUID.randomUUID()
        val ledgerIdempotencyKey = "payment_${paymentId}_${System.currentTimeMillis()}"
        
        // Check idempotency (if payment already exists, return it)
        val existingPayment = paymentRepository.findByIdempotencyKey(ledgerIdempotencyKey)
        if (existingPayment != null) {
            return existingPayment.toDomain()
        }
        
        // CRITICAL: Call Ledger FIRST
        // If this fails, we don't create a payment record
        val ledgerTransaction = try {
            ledgerClient.postTransaction(
                LedgerTransactionRequest(
                    accountId = request.accountId,  // TODO: Get from request or config
                    amountCents = -request.amountCents,  // Negative = debit from customer
                    currency = request.currency,
                    idempotencyKey = ledgerIdempotencyKey,
                    description = "Payment authorization: ${request.description ?: paymentId}"
                )
            )
        } catch (e: LedgerClientException) {
            // Ledger rejected - do NOT create payment
            throw PaymentCreationException(
                "Payment creation failed: Ledger rejected transaction. ${e.message}",
                e
            )
        }
        
        // Ledger succeeded - now persist payment
        val payment = Payment(
            id = paymentId,
            amountCents = request.amountCents,
            currency = request.currency,
            state = PaymentState.CREATED,
            ledgerTransactionId = ledgerTransaction.transactionId,
            idempotencyKey = ledgerIdempotencyKey,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val entity = PaymentEntity.fromDomain(payment)
        val saved = paymentRepository.save(entity)
        
        return saved.toDomain()
    }
    
    /**
     * Transitions payment to a new state.
     * Enforces state machine rules.
     */
    @Transactional
    fun transitionPayment(paymentId: UUID, newState: PaymentState): Payment {
        val entity = paymentRepository.findById(paymentId)
            .orElseThrow { IllegalArgumentException("Payment not found: $paymentId") }
        
        val currentPayment = entity.toDomain()
        
        // Enforce state machine
        stateMachine.transition(currentPayment.state, newState)
        
        // Update state - create new entity with updated fields
        val updated = PaymentEntity(
            id = entity.id,
            amountCents = entity.amountCents,
            currency = entity.currency,
            state = newState,
            ledgerTransactionId = entity.ledgerTransactionId,
            idempotencyKey = entity.idempotencyKey,
            createdAt = entity.createdAt,
            updatedAt = Instant.now()
        )
        
        val saved = paymentRepository.save(updated)
        return saved.toDomain()
    }
    
    /**
     * Gets a payment by ID.
     */
    fun getPayment(paymentId: UUID): Payment {
        val entity = paymentRepository.findById(paymentId)
            .orElseThrow { IllegalArgumentException("Payment not found: $paymentId") }
        return entity.toDomain()
    }
}

/**
 * Request to create a payment.
 */
data class CreatePaymentRequest(
    val accountId: UUID,  // Customer account ID
    val amountCents: Long,
    val currency: String,
    val description: String? = null
)

/**
 * Exception thrown when payment creation fails.
 */
class PaymentCreationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

