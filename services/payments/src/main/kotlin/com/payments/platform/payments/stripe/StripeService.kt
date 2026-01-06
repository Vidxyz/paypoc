package com.payments.platform.payments.stripe

import com.stripe.Stripe
import com.stripe.exception.StripeException
import com.stripe.model.PaymentIntent
import com.stripe.param.PaymentIntentCreateParams
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

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
     * Creates a Stripe PaymentIntent with marketplace split.
     * 
     * Configuration:
     * - capture_method: "manual" (we capture after authorization)
     * - application_fee_amount: platform fee (10% of gross)
     * - transfer_data.destination: seller's Stripe account ID
     * 
     * @param amountCents Total amount in cents
     * @param currency Currency code (e.g., "usd")
     * @param platformFeeCents Platform fee in cents
     * @param sellerStripeAccountId Seller's Stripe connected account ID
     * @param description Payment description
     * @param metadata Additional metadata (e.g., paymentId, buyerId, sellerId)
     * @return Created PaymentIntent with client_secret
     * @throws StripeException if Stripe API call fails
     */
    fun createPaymentIntent(
        amountCents: Long,
        currency: String,
        platformFeeCents: Long,
        sellerStripeAccountId: String,
        description: String?,
        metadata: Map<String, String>
    ): PaymentIntent {
        try {
            val transferDataBuilder = PaymentIntentCreateParams.TransferData.builder()
                .setDestination(sellerStripeAccountId)
            
            val paramsBuilder = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(currency.lowercase())  // Stripe expects lowercase
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)  // Manual capture
                .setApplicationFeeAmount(platformFeeCents)
                .setTransferData(transferDataBuilder.build())
            
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
            
            // Create PaymentIntent - explicitly specify type to resolve overload ambiguity
            val paymentIntent: PaymentIntent = PaymentIntent.create(params)
            
            logger.info(
                "Created Stripe PaymentIntent: ${paymentIntent.id} " +
                "for amount ${amountCents} ${currency} " +
                "(platform fee: ${platformFeeCents}, seller: ${sellerStripeAccountId})"
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
}

/**
 * Exception thrown when Stripe service operations fail.
 */
class StripeServiceException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

