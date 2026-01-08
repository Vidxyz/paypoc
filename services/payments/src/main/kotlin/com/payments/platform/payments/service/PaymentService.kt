package com.payments.platform.payments.service

import com.payments.platform.payments.domain.Payment
import com.payments.platform.payments.domain.PaymentState
import com.payments.platform.payments.domain.PaymentStateMachine
import com.payments.platform.payments.kafka.AuthorizePaymentCommand
import com.payments.platform.payments.kafka.PaymentKafkaProducer
import com.payments.platform.payments.persistence.PaymentEntity
import com.payments.platform.payments.persistence.PaymentRepository
import com.payments.platform.payments.persistence.SellerStripeAccountRepository
import com.payments.platform.payments.stripe.StripeService
import com.payments.platform.payments.stripe.StripeServiceException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Payment orchestration service.
 * 
 * Key principle: Ledger writes ONLY after Stripe confirms money movement.
 * - Payment creation: NO ledger write (no money has moved yet)
 * - Stripe webhook confirms capture: THEN write to ledger
 * - This ensures: "Ledger only records actual money movement"
 */
@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val sellerStripeAccountRepository: SellerStripeAccountRepository,
    private val stateMachine: PaymentStateMachine,
    private val kafkaProducer: PaymentKafkaProducer,
    private val stripeService: StripeService
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    private val PLATFORM_FEE_PERCENTAGE = 0.10  // 10% platform fee
    
    /**
     * Creates a payment and Stripe PaymentIntent WITHOUT writing to ledger.
     * 
     * Order of operations:
     * 1. Generate IDs (paymentId, idempotencyKey)
     * 2. Calculate platform fee (10% of gross)
     * 3. Calculate net seller amount (90% of gross)
     * 4. Create Stripe PaymentIntent with marketplace split
     * 5. Persist payment (state = CREATED, ledger_transaction_id = NULL)
     * 6. Publish AuthorizePayment command to Kafka
     * 7. Return payment with client_secret
     * 
     * NO ledger write at this stage - money hasn't moved yet.
     * 
     * @param request Payment creation request
     * @return Created payment with Stripe PaymentIntent ID and client secret
     * @throws StripeServiceException if Stripe PaymentIntent creation fails
     */
    @Transactional
    fun createPayment(request: CreatePaymentRequest): CreatePaymentResponse {
        // Generate IDs
        val paymentId = UUID.randomUUID()
        val idempotencyKey = "payment_${paymentId}_${System.currentTimeMillis()}"
        
        // Check idempotency (if payment already exists, return it with client secret)
        val existingPayment = paymentRepository.findByIdempotencyKey(idempotencyKey)
        if (existingPayment != null) {
            val existingPaymentIntent = existingPayment.stripePaymentIntentId?.let {
                stripeService.getPaymentIntent(it)
            }
            return CreatePaymentResponse(
                payment = existingPayment.toDomain(),
                clientSecret = existingPaymentIntent?.clientSecret
            )
        }
        
        // Calculate platform fee and net seller amount
        val platformFeeCents = (request.grossAmountCents * PLATFORM_FEE_PERCENTAGE).toLong()
        val netSellerAmountCents = request.grossAmountCents - platformFeeCents
        
        // Validate fee calculation
        require(platformFeeCents >= 0) { "Platform fee cannot be negative" }
        require(netSellerAmountCents > 0) { "Net seller amount must be positive" }
        require(platformFeeCents + netSellerAmountCents == request.grossAmountCents) {
            "Fee calculation error: platform_fee + net_seller_amount must equal gross_amount"
        }
        
        // Lookup seller's Stripe account from database
        val sellerStripeAccount = sellerStripeAccountRepository
            .findBySellerIdAndCurrency(request.sellerId, request.currency)
            ?: throw PaymentCreationException(
                "Seller ${request.sellerId} does not have a Stripe account configured for currency ${request.currency}. " +
                "Please configure the seller's Stripe account before processing payments."
            )
        
        logger.debug(
            "Found Stripe account for seller ${request.sellerId} (${request.currency}): ${sellerStripeAccount.stripeAccountId}"
        )
        
        // Create Stripe PaymentIntent - money goes to platform account
        // Platform will later transfer seller portion via Stripe Transfers API
        val paymentIntent = try {
            stripeService.createPaymentIntent(
                amountCents = request.grossAmountCents,
                currency = request.currency,
                description = request.description ?: "Payment: ${request.buyerId} → ${request.sellerId}",
                metadata = mapOf(
                    "paymentId" to paymentId.toString(),
                    "buyerId" to request.buyerId,
                    "sellerId" to request.sellerId,
                    "idempotencyKey" to idempotencyKey
                )
            )
        } catch (e: StripeServiceException) {
            throw PaymentCreationException("Failed to create Stripe PaymentIntent: ${e.message}", e)
        }
        
        // Persist payment with Stripe PaymentIntent ID (NO ledger write - money hasn't moved yet)
        val payment = Payment(
            id = paymentId,
            buyerId = request.buyerId,
            sellerId = request.sellerId,
            grossAmountCents = request.grossAmountCents,
            platformFeeCents = platformFeeCents,
            netSellerAmountCents = netSellerAmountCents,
            currency = request.currency,
            state = PaymentState.CREATED,
            stripePaymentIntentId = paymentIntent.id,
            ledgerTransactionId = null,  // NULL until capture (ledger write happens after Stripe confirms)
            idempotencyKey = idempotencyKey,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val entity = PaymentEntity.fromDomain(payment)
        val saved = paymentRepository.save(entity)
        
        // Publish AuthorizePayment command to Kafka (async)
        // Client does not wait for this
        try {
            val command = AuthorizePaymentCommand(
                paymentId = saved.id,
                idempotencyKey = saved.idempotencyKey,
                attempt = 1
            )
            kafkaProducer.publishCommand(command)
            logger.info("Published AuthorizePayment command for payment ${saved.id}")
        } catch (e: Exception) {
            // Log error but don't fail the request - payment is already created
            logger.error("Failed to publish AuthorizePayment command for payment ${saved.id}", e)
        }
        
        return CreatePaymentResponse(
            payment = saved.toDomain(),
            clientSecret = paymentIntent.clientSecret
        )
    }
    
    /**
     * Transitions payment to a new state with optional refundedAt timestamp.
     * Used when transitioning to REFUNDED state to also set refundedAt timestamp.
     */
    @Transactional
    fun transitionPaymentWithRefundedAt(paymentId: UUID, newState: PaymentState, refundedAt: Instant? = null): Payment {
        val entity = paymentRepository.findById(paymentId)
            .orElseThrow { IllegalArgumentException("Payment not found: $paymentId") }
        
        val currentPayment = entity.toDomain()
        
        // Enforce state machine
        stateMachine.transition(currentPayment.state, newState)
        
        // Update state and refundedAt - create new entity with updated fields
        val updated = PaymentEntity(
            id = entity.id,
            buyerId = entity.buyerId,
            sellerId = entity.sellerId,
            grossAmountCents = entity.grossAmountCents,
            platformFeeCents = entity.platformFeeCents,
            netSellerAmountCents = entity.netSellerAmountCents,
            currency = entity.currency,
            state = newState,
            stripePaymentIntentId = entity.stripePaymentIntentId,
            ledgerTransactionId = entity.ledgerTransactionId,
            idempotencyKey = entity.idempotencyKey,
            createdAt = entity.createdAt,
            updatedAt = Instant.now(),
            refundedAt = refundedAt ?: entity.refundedAt  // Set refundedAt if provided, otherwise keep existing
        )
        
        val saved = paymentRepository.save(updated)
        return saved.toDomain()
    }
    
    /**
     * Gets the client secret for a payment's Stripe PaymentIntent.
     */
    fun getClientSecret(paymentId: UUID): String {
        val payment = getPayment(paymentId)
        require(payment.stripePaymentIntentId != null) {
            "Payment $paymentId does not have a Stripe PaymentIntent"
        }
        
        val paymentIntent = stripeService.getPaymentIntent(payment.stripePaymentIntentId!!)
        return paymentIntent.clientSecret
            ?: throw IllegalStateException("PaymentIntent ${payment.stripePaymentIntentId} does not have a client_secret")
    }
    
    /**
     * Updates payment with Stripe PaymentIntent ID.
     */
    @Transactional
    fun updateStripePaymentIntentId(paymentId: UUID, stripePaymentIntentId: String): Payment {
        val entity = paymentRepository.findById(paymentId)
            .orElseThrow { IllegalArgumentException("Payment not found: $paymentId") }
        
        val updated = PaymentEntity(
            id = entity.id,
            buyerId = entity.buyerId,
            sellerId = entity.sellerId,
            grossAmountCents = entity.grossAmountCents,
            platformFeeCents = entity.platformFeeCents,
            netSellerAmountCents = entity.netSellerAmountCents,
            currency = entity.currency,
            state = entity.state,
            stripePaymentIntentId = stripePaymentIntentId,
            ledgerTransactionId = entity.ledgerTransactionId,
            idempotencyKey = entity.idempotencyKey,
            createdAt = entity.createdAt,
            updatedAt = Instant.now(),
            refundedAt = entity.refundedAt
        )
        
        return paymentRepository.save(updated).toDomain()
    }
    
    /**
     * Updates payment with ledger transaction ID (after ledger write on capture).
     */
    @Transactional
    fun updateLedgerTransactionId(paymentId: UUID, ledgerTransactionId: UUID): Payment {
        val entity = paymentRepository.findById(paymentId)
            .orElseThrow { IllegalArgumentException("Payment not found: $paymentId") }
        
        val updated = PaymentEntity(
            id = entity.id,
            buyerId = entity.buyerId,
            sellerId = entity.sellerId,
            grossAmountCents = entity.grossAmountCents,
            platformFeeCents = entity.platformFeeCents,
            netSellerAmountCents = entity.netSellerAmountCents,
            currency = entity.currency,
            state = entity.state,
            stripePaymentIntentId = entity.stripePaymentIntentId,
            ledgerTransactionId = ledgerTransactionId,
            idempotencyKey = entity.idempotencyKey,
            createdAt = entity.createdAt,
            updatedAt = Instant.now(),
            refundedAt = entity.refundedAt
        )
        
        return paymentRepository.save(updated).toDomain()
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
            buyerId = entity.buyerId,
            sellerId = entity.sellerId,
            grossAmountCents = entity.grossAmountCents,
            platformFeeCents = entity.platformFeeCents,
            netSellerAmountCents = entity.netSellerAmountCents,
            currency = entity.currency,
            state = newState,
            stripePaymentIntentId = entity.stripePaymentIntentId,
            ledgerTransactionId = entity.ledgerTransactionId,
            idempotencyKey = entity.idempotencyKey,
            createdAt = entity.createdAt,
            updatedAt = Instant.now(),
            refundedAt = entity.refundedAt
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
    
    /**
     * Gets payments for a buyer with pagination and sorting.
     * 
     * @param buyerId The buyer ID to filter by
     * @param page Page number (0-indexed)
     * @param size Page size
     * @param sortBy Field to sort by (default: "createdAt")
     * @param sortDirection Sort direction (ASC or DESC, default: DESC)
     * @return List of payments for the buyer
     */
    fun getPaymentsByBuyerId(
        buyerId: String,
        page: Int = 0,
        size: Int = 50,
        sortBy: String = "createdAt",
        sortDirection: String = "DESC"
    ): List<Payment> {
        val sort = if (sortDirection.uppercase() == "ASC") {
            org.springframework.data.domain.Sort.by(sortBy).ascending()
        } else {
            org.springframework.data.domain.Sort.by(sortBy).descending()
        }
        
        val pageable = org.springframework.data.domain.PageRequest.of(page, size, sort)
        val pageResult = paymentRepository.findByBuyerId(buyerId, pageable)
        
        return pageResult.content.map { it.toDomain() }
    }
    
    /**
     * Checks if a payment has been refunded.
     * 
     * @param paymentId Payment ID
     * @return true if payment has any refunds, false otherwise
     */
    fun isRefunded(paymentId: UUID): Boolean {
        val entity = paymentRepository.findById(paymentId)
            .orElseThrow { IllegalArgumentException("Payment not found: $paymentId") }
        return entity.refundedAt != null
    }
}

/**
 * Request to create a payment.
 */
data class CreatePaymentRequest(
    val buyerId: String,
    val sellerId: String,  // Seller's Stripe account will be looked up from database
    val grossAmountCents: Long,
    val currency: String,
    val description: String? = null
)

/**
 * Response from creating a payment, including client secret for frontend.
 */
data class CreatePaymentResponse(
    val payment: Payment,
    val clientSecret: String?
)

/**
 * Exception thrown when payment creation fails.
 */
class PaymentCreationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
