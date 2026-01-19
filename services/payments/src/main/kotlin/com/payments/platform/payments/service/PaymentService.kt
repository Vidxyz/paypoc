package com.payments.platform.payments.service

import com.payments.platform.payments.domain.Payment
import com.payments.platform.payments.domain.PaymentState
import com.payments.platform.payments.domain.PaymentStateMachine
import com.payments.platform.payments.kafka.AuthorizePaymentCommand
import com.payments.platform.payments.kafka.PaymentKafkaProducer
import com.payments.platform.payments.persistence.ChargebackRepository
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
    private val stripeService: StripeService,
    private val chargebackRepository: ChargebackRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    private val PLATFORM_FEE_PERCENTAGE = 0.10  // 10% platform fee
    
    /**
     * Creates a payment for an order with multiple sellers.
     * 
     * One payment per order - buyer pays once to BuyIt platform.
     * Platform receives payment in Stripe, ledger splits across sellers.
     * 
     * Order of operations:
     * 1. Generate IDs (paymentId, idempotencyKey)
     * 2. Calculate per-seller platform fees (10% of each seller's gross)
     * 3. Calculate per-seller net amounts (90% of each seller's gross)
     * 4. Calculate total platform fee and total net seller amounts
     * 5. Validate seller breakdown sums match total gross
     * 6. Create Stripe PaymentIntent (money goes to platform account)
     * 7. Persist payment with seller breakdown (state = CREATED, ledger_transaction_id = NULL)
     * 8. Publish AuthorizePayment command to Kafka
     * 9. Return payment with client_secret
     * 
     * NO ledger write at this stage - money hasn't moved yet.
     * 
     * @param request Order payment creation request with seller breakdowns
     * @return Created payment with Stripe PaymentIntent ID and client secret
     * @throws StripeServiceException if Stripe PaymentIntent creation fails
     */
    @Transactional
    fun createOrderPayment(request: CreateOrderPaymentRequest): CreatePaymentResponse {
        // Generate IDs
        val paymentId = UUID.randomUUID()
        val idempotencyKey = "order_payment_${request.orderId}_${System.currentTimeMillis()}"
        
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
        
        // Validate seller breakdown sums match total gross
        val totalSellerGross = request.sellerBreakdown.sumOf { it.sellerGrossAmountCents }
        require(totalSellerGross == request.grossAmountCents) {
            "Seller breakdown sum (${totalSellerGross}) must equal gross amount (${request.grossAmountCents})"
        }
        require(request.sellerBreakdown.isNotEmpty()) {
            "Seller breakdown cannot be empty"
        }
        
        // Calculate per-seller breakdowns (platform fee and net amounts)
        val sellerBreakdowns = request.sellerBreakdown.map { sellerPayment ->
            val sellerPlatformFee = (sellerPayment.sellerGrossAmountCents * PLATFORM_FEE_PERCENTAGE).toLong()
            val sellerNetAmount = sellerPayment.sellerGrossAmountCents - sellerPlatformFee
            
            require(sellerPlatformFee >= 0) { "Platform fee cannot be negative for seller ${sellerPayment.sellerId}" }
            require(sellerNetAmount > 0) { "Net seller amount must be positive for seller ${sellerPayment.sellerId}" }
            
            com.payments.platform.payments.domain.SellerBreakdown(
                sellerId = sellerPayment.sellerId,
                sellerGrossAmountCents = sellerPayment.sellerGrossAmountCents,
                platformFeeCents = sellerPlatformFee,
                netSellerAmountCents = sellerNetAmount
            )
        }
        
        // Calculate totals
        val totalPlatformFee = sellerBreakdowns.sumOf { it.platformFeeCents }
        val totalNetSellerAmount = sellerBreakdowns.sumOf { it.netSellerAmountCents }
        
        // Validate totals
        require(totalPlatformFee >= 0) { "Total platform fee cannot be negative" }
        require(totalNetSellerAmount > 0) { "Total net seller amount must be positive" }
        require(totalPlatformFee + totalNetSellerAmount == request.grossAmountCents) {
            "Fee calculation error: totalPlatformFee + totalNetSellerAmount must equal grossAmountCents"
        }
        
        // Create Stripe PaymentIntent - money goes to platform account
        // No seller Stripe account lookup needed - payment goes to platform
        val paymentIntent = try {
            stripeService.createPaymentIntent(
                amountCents = request.grossAmountCents,
                currency = request.currency,
                description = request.description ?: "Payment for order ${request.orderId}",
                metadata = mapOf(
                    "paymentId" to paymentId.toString(),
                    "orderId" to request.orderId.toString(),
                    "buyerId" to request.buyerId,
                    "idempotencyKey" to idempotencyKey
                )
            )
        } catch (e: StripeServiceException) {
            throw PaymentCreationException("Failed to create Stripe PaymentIntent: ${e.message}", e)
        }
        
        // Persist payment with Stripe PaymentIntent ID (NO ledger write - money hasn't moved yet)
        val payment = Payment(
            id = paymentId,
            orderId = request.orderId,
            buyerId = request.buyerId,
            grossAmountCents = request.grossAmountCents,
            platformFeeCents = totalPlatformFee,
            netSellerAmountCents = totalNetSellerAmount,
            currency = request.currency,
            sellerBreakdown = sellerBreakdowns,
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
            logger.info("Published AuthorizePayment command for order payment ${saved.id} (order: ${request.orderId})")
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
            orderId = entity.orderId,
            buyerId = entity.buyerId,
            grossAmountCents = entity.grossAmountCents,
            platformFeeCents = entity.platformFeeCents,
            netSellerAmountCents = entity.netSellerAmountCents,
            currency = entity.currency,
            sellerBreakdown = entity.sellerBreakdown,
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
            orderId = entity.orderId,
            buyerId = entity.buyerId,
            grossAmountCents = entity.grossAmountCents,
            platformFeeCents = entity.platformFeeCents,
            netSellerAmountCents = entity.netSellerAmountCents,
            currency = entity.currency,
            sellerBreakdown = entity.sellerBreakdown,
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
            orderId = entity.orderId,
            buyerId = entity.buyerId,
            grossAmountCents = entity.grossAmountCents,
            platformFeeCents = entity.platformFeeCents,
            netSellerAmountCents = entity.netSellerAmountCents,
            currency = entity.currency,
            sellerBreakdown = entity.sellerBreakdown,
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
            orderId = entity.orderId,
            buyerId = entity.buyerId,
            grossAmountCents = entity.grossAmountCents,
            platformFeeCents = entity.platformFeeCents,
            netSellerAmountCents = entity.netSellerAmountCents,
            currency = entity.currency,
            sellerBreakdown = entity.sellerBreakdown,
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
     * Gets a payment by order ID.
     */
    fun getPaymentByOrderId(orderId: UUID): Payment {
        val entity = paymentRepository.findByOrderId(orderId)
            ?: throw IllegalArgumentException("Payment not found for order: $orderId")
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
     * Gets payments for a seller with pagination and sorting.
     * 
     * @param sellerId The seller ID to filter by
     * @param page Page number (0-indexed)
     * @param size Page size
     * @param sortBy Field to sort by (default: "createdAt")
     * @param sortDirection Sort direction (ASC or DESC, default: DESC)
     * @return List of payments for the seller
     */
    fun getPaymentsBySellerId(
        sellerId: String,
        page: Int = 0,
        size: Int = 50,
        sortBy: String = "createdAt",
        sortDirection: String = "DESC"
    ): List<Payment> {
        // Use manual pagination with native query to avoid Spring Data JPA trying to add its own ORDER BY
        // The query already has ORDER BY p.created_at DESC, so we just need LIMIT/OFFSET
        val limit = size
        val offset = page.toLong() * size.toLong()
        val payments = paymentRepository.findBySellerId(sellerId, limit, offset)
        
        return payments.map { it.toDomain() }
    }
    
    /**
     * Gets the total count of payments for a seller.
     * Used for pagination metadata.
     */
    fun countPaymentsBySellerId(sellerId: String): Long {
        return paymentRepository.countBySellerId(sellerId)
    }
    
    /**
     * Gets all payments (admin-only).
     * 
     * @param pageable Pagination and sorting parameters
     * @return Page of all payments
     */
    fun getAllPayments(pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<Payment> {
        val pageResult = paymentRepository.findAll(pageable)
        return pageResult.map { it.toDomain() }
    }
    
    /**
     * Gets chargeback summary information for a list of payments.
     * Returns a map of paymentId -> ChargebackInfo.
     * 
     * This is optimized to fetch all chargebacks in a single query per payment.
     */
    fun getChargebackInfoForPayments(paymentIds: List<UUID>): Map<UUID, com.payments.platform.payments.api.ChargebackInfo> {
        if (paymentIds.isEmpty()) {
            return emptyMap()
        }
        
        // Fetch all chargebacks for these payments in one query
        val allChargebacks = paymentIds.flatMap { paymentId ->
            chargebackRepository.findByPaymentId(paymentId).map { chargeback ->
                paymentId to chargeback.toDomain()
            }
        }
        
        // Group by payment ID and get the latest chargeback (by created_at)
        return allChargebacks
            .groupBy { it.first }
            .mapValues { (_, chargebacks) ->
                val latestChargeback = chargebacks.maxByOrNull { it.second.createdAt }?.second
                if (latestChargeback != null) {
                    com.payments.platform.payments.api.ChargebackInfo(
                        hasChargeback = true,
                        chargebackId = latestChargeback.id,
                        state = latestChargeback.state.name,
                        amountCents = latestChargeback.chargebackAmountCents
                    )
                } else {
                    com.payments.platform.payments.api.ChargebackInfo(hasChargeback = false)
                }
            }
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
 * Request to create a payment for an order with multiple sellers.
 */
data class CreateOrderPaymentRequest(
    val orderId: UUID,
    val buyerId: String,
    val grossAmountCents: Long,
    val currency: String,
    val sellerBreakdown: List<SellerPaymentBreakdown>,  // Per-seller gross amounts
    val description: String? = null
)

/**
 * Seller payment breakdown (input - only gross amount, fees calculated).
 */
data class SellerPaymentBreakdown(
    val sellerId: String,
    val sellerGrossAmountCents: Long  // This seller's portion of the total order
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
