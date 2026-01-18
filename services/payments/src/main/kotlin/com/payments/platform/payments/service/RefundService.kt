package com.payments.platform.payments.service

import com.payments.platform.payments.domain.ChargebackState
import com.payments.platform.payments.domain.PaymentState
import com.payments.platform.payments.domain.Refund
import com.payments.platform.payments.domain.RefundState
import com.payments.platform.payments.persistence.ChargebackRepository
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
    private val paymentService: PaymentService,
    private val chargebackRepository: ChargebackRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val PLATFORM_FEE_PERCENTAGE = 0.10  // 10% platform fee
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
        
        // Validate chargeback status
        // Refunds are only allowed if:
        // - Payment has no chargebacks, OR
        // - Payment has chargeback(s) but the latest one is WON or WARNING_CLOSED (platform won)
        val chargebacks = chargebackRepository.findByPaymentId(paymentId)
        if (chargebacks.isNotEmpty()) {
            // Get the latest chargeback (most recent by createdAt)
            val latestChargeback = chargebacks.maxByOrNull { it.createdAt }
            if (latestChargeback != null) {
                val chargebackState = latestChargeback.state
                // Only allow refund if chargeback was WON or WARNING_CLOSED (platform won)
                if (chargebackState != ChargebackState.WON &&
                    chargebackState != ChargebackState.WARNING_CLOSED) {
                    throw RefundCreationException(
                        "Payment $paymentId cannot be refunded due to active chargeback. " +
                        "Current chargeback state: $chargebackState. " +
                        "Only payments with WON or WARNING_CLOSED chargebacks can be refunded."
                    )
                }
            }
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
        
        // Create seller refund breakdown from payment's seller breakdown (full refund)
        val sellerRefundBreakdown = payment.sellerBreakdown.map { seller ->
            com.payments.platform.payments.domain.SellerRefundBreakdown(
                sellerId = seller.sellerId,
                refundAmountCents = seller.sellerGrossAmountCents,
                platformFeeRefundCents = seller.platformFeeCents,
                netSellerRefundCents = seller.netSellerAmountCents
            )
        }
        
        // Persist refund with Stripe Refund ID (NO ledger write - money hasn't moved yet)
        // For full refunds, orderItemsRefunded is null (order service will treat null as full refund)
        val refund = Refund(
            id = refundId,
            paymentId = paymentId,
            refundAmountCents = refundAmountCents,
            platformFeeRefundCents = platformFeeRefundCents,
            netSellerRefundCents = netSellerRefundCents,
            currency = payment.currency,
            sellerRefundBreakdown = sellerRefundBreakdown,
            orderItemsRefunded = null,  // Full refund - order service will get all items
            state = RefundState.REFUNDING,
            stripeRefundId = stripeRefund.id,
            ledgerTransactionId = null,  // NULL until refund confirmed (ledger write happens after Stripe confirms)
            idempotencyKey = idempotencyKey,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val entity = RefundEntity.fromDomain(refund)
        val saved = refundRepository.save(entity)
        
        // Transition payment from CAPTURED to REFUNDING
        try {
            paymentService.transitionPayment(payment.id, PaymentState.REFUNDING)
            logger.info("Payment $paymentId transitioned to REFUNDING state")
        } catch (e: Exception) {
            logger.error("Failed to transition payment $paymentId to REFUNDING state", e)
            // Don't fail the refund creation - refund is created, payment state can be corrected later
        }
        
        logger.info(
            "Created refund ${refund.id} for payment $paymentId " +
            "(Stripe Refund ID: ${stripeRefund.id}, amount: $refundAmountCents ${payment.currency})"
        )
        
        return saved.toDomain()
    }
    
    /**
     * Creates a partial refund for a payment with multiple sellers.
     * 
     * Order of operations:
     * 1. Validate payment exists and is CAPTURED
     * 2. Validate refund amounts don't exceed original seller amounts
     * 3. Generate refund IDs and idempotency key
     * 4. Calculate per-seller platform fee refunds (10% of each seller's refund)
     * 5. Calculate total platform fee refund and total net seller refund
     * 6. Create Stripe Refund (partial refund)
     * 7. Persist refund with seller refund breakdown (state = REFUNDING, ledger_transaction_id = NULL)
     * 8. Return refund
     * 
     * NO ledger write at this stage - money hasn't moved yet.
     * 
     * The payment is looked up by orderId internally.
     * 
     * @param orderId Order ID to refund (payment is looked up by this)
     * @param request Partial refund request with order items to refund
     * @return Created refund
     * @throws RefundCreationException if refund creation fails
     */
    @Transactional
    fun createPartialRefund(
        orderId: UUID,
        request: com.payments.platform.payments.api.internal.CreatePartialRefundRequest
    ): Refund {
        // Find payment by orderId
        val payment = try {
            paymentService.getPaymentByOrderId(orderId)
        } catch (e: IllegalArgumentException) {
            throw RefundCreationException("Payment not found for order: $orderId")
        }
        
        // Validate payment state
        if (payment.state != PaymentState.CAPTURED) {
            throw RefundCreationException(
                "Payment for order $orderId cannot be refunded. Current state: ${payment.state}. " +
                "Only CAPTURED payments can be refunded."
            )
        }
        
        // Validate order items to refund
        require(request.orderItemsToRefund.isNotEmpty()) {
            "Order items to refund cannot be empty"
        }
        
        // Validate quantities are positive
        for (itemRefund in request.orderItemsToRefund) {
            require(itemRefund.quantity > 0) {
                "Refund quantity must be positive for order item ${itemRefund.orderItemId}"
            }
            require(itemRefund.priceCents > 0) {
                "Price per item must be positive for order item ${itemRefund.orderItemId}"
            }
        }
        
        // Calculate seller refund amounts from order items
        // Group items by seller and sum up refund amounts
        val sellerRefundAmounts = request.orderItemsToRefund
            .groupBy { it.sellerId }
            .mapValues { (_, items) ->
                items.sumOf { it.priceCents * it.quantity }
            }
        
        // Validate sellers exist in original payment
        val sellerAmountsMap = payment.sellerBreakdown.associateBy { it.sellerId }
        for ((sellerId, refundAmount) in sellerRefundAmounts) {
            val originalSeller = sellerAmountsMap[sellerId]
                ?: throw RefundCreationException(
                    "Seller $sellerId not found in original payment. " +
                    "Cannot refund items from seller that wasn't part of the original order."
                )
            require(refundAmount <= originalSeller.sellerGrossAmountCents) {
                "Refund amount for seller $sellerId ($refundAmount) " +
                "exceeds original amount (${originalSeller.sellerGrossAmountCents})"
            }
        }
        
        // Calculate per-seller refund breakdowns (platform fee and net amounts)
        val sellerRefundBreakdowns = sellerRefundAmounts.map { (sellerId, sellerRefundAmount) ->
            val sellerPlatformFeeRefund = (sellerRefundAmount * PLATFORM_FEE_PERCENTAGE).toLong()
            val sellerNetRefund = sellerRefundAmount - sellerPlatformFeeRefund
            
            require(sellerPlatformFeeRefund >= 0) { "Platform fee refund cannot be negative for seller $sellerId" }
            require(sellerNetRefund > 0) { "Net seller refund must be positive for seller $sellerId" }
            
            sellerId to Triple(sellerRefundAmount, sellerPlatformFeeRefund, sellerNetRefund)
        }.toMap()
        
        // Calculate total refund amount (sum of all item refunds)
        val totalRefundAmount = request.orderItemsToRefund.sumOf { it.priceCents * it.quantity }
        
        // Calculate totals
        val totalPlatformFeeRefund = sellerRefundBreakdowns.values.sumOf { it.second }
        val totalNetSellerRefund = sellerRefundBreakdowns.values.sumOf { it.third }
        
        // Validate totals
        require(totalPlatformFeeRefund >= 0) { "Total platform fee refund cannot be negative" }
        require(totalNetSellerRefund > 0) { "Total net seller refund must be positive" }
        require(totalPlatformFeeRefund + totalNetSellerRefund == totalRefundAmount) {
            "Refund calculation error: totalPlatformFeeRefund + totalNetSellerRefund must equal totalRefundAmount"
        }
        
        // Validate payment has Stripe PaymentIntent ID
        val stripePaymentIntentId = payment.stripePaymentIntentId
            ?: throw RefundCreationException("Payment ${payment.id} (order: $orderId) has no Stripe PaymentIntent ID")
        
        // Generate refund ID and idempotency key
        val refundId = UUID.randomUUID()
        val idempotencyKey = "partial_refund_${refundId}_${System.currentTimeMillis()}"
        
        // Check idempotency (if refund already exists, return it)
        val existingRefund = refundRepository.findByIdempotencyKey(idempotencyKey)
        if (existingRefund != null) {
            logger.info("Refund with idempotency key $idempotencyKey already exists, returning existing refund")
            return existingRefund.toDomain()
        }
        
        // Create Stripe Refund (partial refund)
        val stripeRefund = try {
            stripeService.createRefund(
                paymentIntentId = stripePaymentIntentId,
                amountCents = totalRefundAmount  // Partial refund amount (calculated from items)
            )
        } catch (e: StripeServiceException) {
            throw RefundCreationException("Failed to create Stripe Refund: ${e.message}", e)
        }
        
        // Create seller refund breakdown from calculated breakdowns
        val sellerRefundBreakdown = sellerRefundBreakdowns.map { (sellerId, breakdown) ->
            com.payments.platform.payments.domain.SellerRefundBreakdown(
                sellerId = sellerId,
                refundAmountCents = breakdown.first,
                platformFeeRefundCents = breakdown.second,
                netSellerRefundCents = breakdown.third
            )
        }
        
        // Create order items refunded snapshot
        val orderItemsRefunded = request.orderItemsToRefund.map { item ->
            com.payments.platform.payments.domain.OrderItemRefundSnapshot(
                orderItemId = item.orderItemId,
                quantity = item.quantity,
                sellerId = item.sellerId,
                priceCents = item.priceCents
            )
        }
        
        // Persist refund with Stripe Refund ID (NO ledger write - money hasn't moved yet)
        val refund = Refund(
            id = refundId,
            paymentId = payment.id,
            refundAmountCents = totalRefundAmount,
            platformFeeRefundCents = totalPlatformFeeRefund,
            netSellerRefundCents = totalNetSellerRefund,
            currency = payment.currency,
            sellerRefundBreakdown = sellerRefundBreakdown,
            orderItemsRefunded = orderItemsRefunded,
            state = RefundState.REFUNDING,
            stripeRefundId = stripeRefund.id,
            ledgerTransactionId = null,  // NULL until refund confirmed (ledger write happens after Stripe confirms)
            idempotencyKey = idempotencyKey,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val entity = RefundEntity.fromDomain(refund)
        val saved = refundRepository.save(entity)
        
        // Transition payment from CAPTURED to REFUNDING (if this is the first refund)
        // Note: For partial refunds, payment might remain CAPTURED if not fully refunded
        // For now, we'll transition to REFUNDING to indicate a refund is in progress
        try {
            if (payment.state == PaymentState.CAPTURED) {
                paymentService.transitionPayment(payment.id, PaymentState.REFUNDING)
                logger.info("Payment ${payment.id} (order: $orderId) transitioned to REFUNDING state")
            }
        } catch (e: Exception) {
            logger.error("Failed to transition payment ${payment.id} (order: $orderId) to REFUNDING state", e)
            // Don't fail the refund creation - refund is created, payment state can be corrected later
        }
        
        val sellerIds = sellerRefundAmounts.keys.joinToString(", ")
        logger.info(
            "Created partial refund ${refund.id} for payment ${payment.id} (order: $orderId) " +
            "(Stripe Refund ID: ${stripeRefund.id}, amount: $totalRefundAmount ${payment.currency}, " +
            "items: ${request.orderItemsToRefund.size}, sellers: $sellerIds)"
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
    
    /**
     * Updates the ledger transaction ID for a refund.
     * 
     * This is called after the ledger service has created a transaction for the refund.
     * 
     * @param refundId Refund ID
     * @param ledgerTransactionId Ledger transaction ID
     * @return Updated refund
     */
    @Transactional
    fun updateLedgerTransactionId(refundId: UUID, ledgerTransactionId: UUID): Refund {
        val entity = refundRepository.findById(refundId).orElseThrow {
            IllegalArgumentException("Refund not found: $refundId")
        }
        
        entity.ledgerTransactionId = ledgerTransactionId
        entity.updatedAt = Instant.now()
        
        val saved = refundRepository.save(entity)
        logger.info("Updated refund $refundId with ledger transaction ID: $ledgerTransactionId")
        
        return saved.toDomain()
    }
}

/**
 * Exception thrown when refund creation fails.
 */
class RefundCreationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

