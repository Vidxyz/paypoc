package com.payments.platform.payments.stripe

import com.stripe.Stripe
import com.stripe.exception.StripeException
import com.stripe.model.BalanceTransaction
import com.stripe.model.PaymentIntent
import com.stripe.model.Transfer
import com.stripe.param.BalanceTransactionListParams
import com.stripe.param.PaymentIntentCreateParams
import com.stripe.param.TransferCreateParams
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for interacting with Stripe API.
 * 
 * Handles:
 * - PaymentIntent creation
 * - PaymentIntent retrieval
 * - Webhook signature verification (via Stripe SDK)
 */
@Service
class StripeService(
    @Value("\${stripe.secret-key}") private val secretKey: String,
    @Value("\${stripe.webhook-secret:}") private val webhookSecret: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    @PostConstruct
    fun initialize() {
        Stripe.apiKey = secretKey
        logger.info("Stripe SDK initialized with API key (ending in ...${secretKey.takeLast(4)})")
    }
    
    /**
     * Creates a Stripe PaymentIntent that collects money to the platform account.
     * 
     * Configuration:
     * - capture_method: "manual" (we capture after authorization)
     * - NO destination: money goes to platform account (not seller)
     * - NO application_fee: we'll handle split via transfers later
     * 
     * Money flow: Buyer → Platform Stripe Account
     * Platform will later transfer seller portion via Stripe Transfers API.
     * 
     * @param amountCents Total amount in cents
     * @param currency Currency code (e.g., "usd")
     * @param description Payment description
     * @param metadata Additional metadata (e.g., paymentId, buyerId, sellerId)
     * @return Created PaymentIntent with client_secret
     * @throws StripeException if Stripe API call fails
     */
    fun createPaymentIntent(
        amountCents: Long,
        currency: String,
        description: String?,
        metadata: Map<String, String>
    ): PaymentIntent {
        try {
            // Configure automatic payment methods to disallow redirects
            // This prevents the need for return_url when confirming with card payment methods
            val automaticPaymentMethodsBuilder = PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                .setEnabled(true)
                .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
            
            val paramsBuilder = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(currency.lowercase())  // Stripe expects lowercase
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)  // Manual capture
                .setAutomaticPaymentMethods(automaticPaymentMethodsBuilder.build())
            
            if (description != null) {
                paramsBuilder.setDescription(description)
            }
            
            // Add metadata if provided
            if (metadata.isNotEmpty()) {
                metadata.forEach { (key, value) ->
                    paramsBuilder.putMetadata(key, value)
                }
            }
            
            val params: PaymentIntentCreateParams = paramsBuilder.build()
            
            // Create PaymentIntent - money goes to platform account
            val paymentIntent: PaymentIntent = PaymentIntent.create(params)
            
            logger.info(
                "Created Stripe PaymentIntent: ${paymentIntent.id} " +
                "for amount ${amountCents} ${currency} " +
                "(money collected to platform account)"
            )
            
            return paymentIntent
        } catch (e: StripeException) {
            logger.error("Failed to create Stripe PaymentIntent: ${e.message}", e)
            throw StripeServiceException("Failed to create Stripe PaymentIntent: ${e.message}", e)
        }
    }
    
    /**
     * Retrieves a PaymentIntent by ID.
     */
    fun getPaymentIntent(paymentIntentId: String): PaymentIntent {
        try {
            return PaymentIntent.retrieve(paymentIntentId)
        } catch (e: StripeException) {
            logger.error("Failed to retrieve Stripe PaymentIntent $paymentIntentId: ${e.message}", e)
            throw StripeServiceException("Failed to retrieve Stripe PaymentIntent: ${e.message}", e)
        }
    }
    
    /**
     * Captures a PaymentIntent (for manual capture mode).
     * 
     * @param paymentIntentId The PaymentIntent ID to capture
     * @return The captured PaymentIntent
     * @throws StripeServiceException if capture fails
     */
    fun capturePaymentIntent(paymentIntentId: String): PaymentIntent {
        try {
            val paymentIntent = PaymentIntent.retrieve(paymentIntentId)
            val captured = paymentIntent.capture()
            logger.info("Captured Stripe PaymentIntent: $paymentIntentId")
            return captured
        } catch (e: StripeException) {
            logger.error("Failed to capture Stripe PaymentIntent $paymentIntentId: ${e.message}", e)
            throw StripeServiceException("Failed to capture Stripe PaymentIntent: ${e.message}", e)
        }
    }
    
    /**
     * Creates a Stripe Refund for a PaymentIntent.
     * 
     * For full refunds, amountCents should be null (Stripe will refund the full amount).
     * 
     * @param paymentIntentId The PaymentIntent ID to refund
     * @param amountCents Optional amount to refund in cents (null for full refund)
     * @return Created Refund
     * @throws StripeException if Stripe API call fails
     */
    fun createRefund(paymentIntentId: String, amountCents: Long? = null): com.stripe.model.Refund {
        try {
            val paramsBuilder = com.stripe.param.RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
            
            // If amountCents is provided, set it (otherwise Stripe refunds full amount)
            if (amountCents != null) {
                paramsBuilder.setAmount(amountCents)
            }
            
            val params = paramsBuilder.build()
            val refund = com.stripe.model.Refund.create(params)
            
            logger.info(
                "Created Stripe Refund: ${refund.id} for PaymentIntent: $paymentIntentId " +
                "(amount: ${refund.amount ?: "full"})"
            )
            
            return refund
        } catch (e: StripeException) {
            logger.error("Failed to create Stripe Refund for PaymentIntent $paymentIntentId: ${e.message}", e)
            throw StripeServiceException("Failed to create Stripe Refund: ${e.message}", e)
        }
    }
    
    /**
     * Retrieves a Refund by ID.
     */
    fun getRefund(refundId: String): com.stripe.model.Refund {
        try {
            return com.stripe.model.Refund.retrieve(refundId)
        } catch (e: StripeException) {
            logger.error("Failed to retrieve Stripe Refund $refundId: ${e.message}", e)
            throw StripeServiceException("Failed to retrieve Stripe Refund: ${e.message}", e)
        }
    }
    
    /**
     * Creates a Stripe Transfer to send money from platform account to seller account.
     * 
     * This is used for payouts after payment is captured.
     * Money flows: Platform Stripe Account → Seller Stripe Account
     * 
     * @param amountCents Amount to transfer in cents
     * @param currency Currency code (e.g., "usd")
     * @param destinationAccountId Seller's Stripe connected account ID
     * @param metadata Additional metadata (e.g., payoutId, paymentId, sellerId)
     * @return Created Transfer
     * @throws StripeException if Stripe API call fails
     */
    fun createTransfer(
        amountCents: Long,
        currency: String,
        destinationAccountId: String,
        metadata: Map<String, String> = emptyMap()
    ): Transfer {
        try {
            val paramsBuilder = TransferCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(currency.lowercase())  // Stripe expects lowercase
                .setDestination(destinationAccountId)
            
            // Add metadata if provided
            if (metadata.isNotEmpty()) {
                metadata.forEach { (key, value) ->
                    paramsBuilder.putMetadata(key, value)
                }
            }
            
            val params = paramsBuilder.build()
            val transfer = Transfer.create(params)
            
            logger.info(
                "Created Stripe Transfer: ${transfer.id} " +
                "for amount ${amountCents} ${currency} " +
                "to seller account: ${destinationAccountId}"
            )
            
            return transfer
        } catch (e: StripeException) {
            logger.error("Failed to create Stripe Transfer: ${e.message}", e)
            throw StripeServiceException("Failed to create Stripe Transfer: ${e.message}", e)
        }
    }
    
    /**
     * Retrieves a Transfer by ID.
     */
    fun getTransfer(transferId: String): Transfer {
        try {
            return Transfer.retrieve(transferId)
        } catch (e: StripeException) {
            logger.error("Failed to retrieve Stripe Transfer $transferId: ${e.message}", e)
            throw StripeServiceException("Failed to retrieve Stripe Transfer: ${e.message}", e)
        }
    }
    
    /**
     * Retrieves a Dispute by ID.
     */
    fun getDispute(disputeId: String): com.stripe.model.Dispute {
        try {
            return com.stripe.model.Dispute.retrieve(disputeId)
        } catch (e: StripeException) {
            logger.error("Failed to retrieve Stripe Dispute $disputeId: ${e.message}", e)
            throw StripeServiceException("Failed to retrieve Stripe Dispute: ${e.message}", e)
        }
    }
    
    /**
     * Gets the webhook secret for signature verification.
     */
    fun getWebhookSecret(): String {
        return webhookSecret
    }
    
    /**
     * Verifies a Stripe webhook signature.
     * 
     * @param payload Raw request body
     * @param signature Stripe signature header
     * @return true if signature is valid, false otherwise
     */
    fun verifyWebhookSignature(payload: String, signature: String): Boolean {
        if (webhookSecret.isBlank()) {
            logger.warn("Webhook secret not configured - skipping signature verification")
            return true  // In development, allow without verification
        }
        
        return try {
            com.stripe.net.Webhook.Signature.verifyHeader(
                payload,
                signature,
                webhookSecret,
                300L  // 5 minute tolerance
            )
        } catch (e: Exception) {
            logger.error("Webhook signature verification failed: ${e.message}", e)
            false
        }
    }
    
    /**
     * Lists Stripe Balance Transactions for a given date range.
     * 
     * This is used for reconciliation to compare Stripe's financial records with our ledger.
     * 
     * Best Practices:
     * - Uses `created` parameter for date filtering (not `available_on` - that's when funds become available)
     * - Handles pagination automatically (Stripe returns paginated results)
     * - Respects rate limits (100 requests per second)
     * 
     * @param startDate Start date (inclusive) for filtering transactions
     * @param endDate End date (inclusive) for filtering transactions
     * @param currency Optional currency filter (ISO 4217, lowercase, e.g., "usd")
     * @return List of Balance Transactions in the specified date range
     * @throws StripeServiceException if Stripe API call fails
     */
    fun listBalanceTransactions(
        startDate: Instant,
        endDate: Instant,
        currency: String? = null
    ): List<BalanceTransaction> {
        try {
            val allTransactions = mutableListOf<BalanceTransaction>()
            
            val paramsBuilder = BalanceTransactionListParams.builder()
                .setCreated(
                    BalanceTransactionListParams.Created.builder()
                        .setGte(startDate.epochSecond.toLong())
                        .setLte(endDate.epochSecond.toLong())
                        .build()
                )
                .setLimit(100L)  // Maximum per page
            
            if (currency != null) {
                paramsBuilder.setCurrency(currency.lowercase())
            }
            
            var hasMore = true
            var startingAfter: String? = null
            
            while (hasMore) {
                val params = if (startingAfter != null) {
                    paramsBuilder.setStartingAfter(startingAfter).build()
                } else {
                    paramsBuilder.build()
                }
                
                val response = BalanceTransaction.list(params)
                allTransactions.addAll(response.data)
                
                hasMore = response.hasMore
                startingAfter = if (hasMore && response.data.isNotEmpty()) {
                    response.data.last().id
                } else {
                    null
                }
                
                // Small delay to respect rate limits (100 req/sec = ~10ms between requests)
                if (hasMore) {
                    Thread.sleep(20)  // Conservative delay
                }
            }
            
            logger.info(
                "Fetched ${allTransactions.size} Stripe balance transactions " +
                "from ${startDate} to ${endDate}${if (currency != null) " (currency: $currency)" else ""}"
            )
            
            return allTransactions
        } catch (e: StripeException) {
            logger.error("Failed to list Stripe balance transactions: ${e.message}", e)
            throw StripeServiceException("Failed to list Stripe balance transactions: ${e.message}", e)
        }
    }
}

/**
 * Exception thrown when Stripe service operations fail.
 */
class StripeServiceException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

