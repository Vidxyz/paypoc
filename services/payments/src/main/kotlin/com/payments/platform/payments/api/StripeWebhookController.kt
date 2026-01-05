package com.payments.platform.payments.api

import com.payments.platform.payments.domain.PaymentState
import com.payments.platform.payments.kafka.CapturePaymentCommand
import com.payments.platform.payments.kafka.PaymentKafkaProducer
import com.payments.platform.payments.persistence.PaymentRepository
import com.payments.platform.payments.service.PaymentService
import com.payments.platform.payments.stripe.StripeService
import com.stripe.model.Event
import com.stripe.model.PaymentIntent
import com.stripe.net.Webhook
import io.swagger.v3.oas.annotations.Hidden
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Webhook controller for Stripe events.
 * 
 * Handles:
 * - payment_intent.succeeded: Payment was captured successfully
 * - payment_intent.payment_failed: Payment authorization failed
 * - payment_intent.amount_capturable_updated: Payment was authorized (ready to capture)
 * 
 * All webhook handlers are idempotent and validate webhook signatures.
 */
@RestController
@RequestMapping("/webhooks/stripe")
@Hidden  // Hide from Swagger UI (internal endpoint)
class StripeWebhookController(
    private val stripeService: StripeService,
    private val paymentRepository: PaymentRepository,
    private val paymentService: PaymentService,
    private val kafkaProducer: PaymentKafkaProducer
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    /**
     * POST /webhooks/stripe
     * Handles Stripe webhook events.
     * 
     * Flow:
     * 1. Verify webhook signature
     * 2. Parse event
     * 3. Handle event type (payment_intent.succeeded, etc.)
     * 4. Publish Kafka command to process asynchronously
     */
    @PostMapping
    fun handleWebhook(
        @RequestBody payload: String,
        @RequestHeader("Stripe-Signature") signature: String
    ): ResponseEntity<String> {
        return try {
            // Verify webhook signature
            if (!stripeService.verifyWebhookSignature(payload, signature)) {
                logger.warn("Invalid webhook signature - rejecting webhook")
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature")
            }
            
            // Parse event
            val event = Webhook.constructEvent(payload, signature, stripeService.getWebhookSecret())
            
            logger.info("Received Stripe webhook: ${event.type} (id: ${event.id})")
            
            // Handle event
            when (event.type) {
                "payment_intent.succeeded" -> handlePaymentIntentSucceeded(event)
                "payment_intent.payment_failed" -> handlePaymentIntentFailed(event)
                "payment_intent.amount_capturable_updated" -> handlePaymentIntentAuthorized(event)
                else -> {
                    logger.debug("Unhandled webhook event type: ${event.type}")
                }
            }
            
            ResponseEntity.ok("Webhook processed")
        } catch (e: Exception) {
            logger.error("Error processing Stripe webhook: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Webhook processing failed")
        }
    }
    
    /**
     * Handles payment_intent.succeeded event.
     * 
     * This means the payment was captured successfully.
     * Publishes CapturePaymentCommand to trigger ledger write.
     */
    private fun handlePaymentIntentSucceeded(event: Event) {
        val paymentIntent = event.dataObjectDeserializer.deserializeUnsafe() as PaymentIntent
        val paymentIntentId = paymentIntent.id
        
        logger.info("PaymentIntent succeeded: $paymentIntentId")
        
        // Find payment by Stripe PaymentIntent ID
        val paymentEntity = paymentRepository.findByStripePaymentIntentId(paymentIntentId)
            ?: run {
                logger.warn("Payment not found for PaymentIntent: $paymentIntentId")
                return
            }
        
        val payment = paymentEntity.toDomain()
        
        // Idempotency check: if already captured, skip
        if (payment.state == PaymentState.CAPTURED) {
            logger.info("Payment ${payment.id} already captured, skipping webhook processing")
            return
        }
        
        // Validate state: must be AUTHORIZED
        if (payment.state != PaymentState.AUTHORIZED) {
            logger.warn(
                "Payment ${payment.id} is in state ${payment.state}, " +
                "cannot capture. Expected AUTHORIZED. PaymentIntent: $paymentIntentId"
            )
            // Transition to FAILED if in unexpected state
            try {
                paymentService.transitionPayment(payment.id, PaymentState.FAILED)
            } catch (e: Exception) {
                logger.error("Failed to transition payment ${payment.id} to FAILED", e)
            }
            return
        }
        
        // Publish CapturePaymentCommand to Kafka
        // This will trigger the ledger write
        val captureCommand = CapturePaymentCommand(
            paymentId = payment.id,
            idempotencyKey = payment.idempotencyKey,
            attempt = 1
        )
        kafkaProducer.publishCommand(captureCommand)
        
        logger.info("Published CapturePaymentCommand for payment ${payment.id} (PaymentIntent: $paymentIntentId)")
    }
    
    /**
     * Handles payment_intent.payment_failed event.
     * 
     * This means the payment authorization failed.
     * Transitions payment to FAILED state.
     */
    private fun handlePaymentIntentFailed(event: Event) {
        val paymentIntent = event.dataObjectDeserializer.deserializeUnsafe() as PaymentIntent
        val paymentIntentId = paymentIntent.id
        
        logger.info("PaymentIntent failed: $paymentIntentId")
        
        // Find payment by Stripe PaymentIntent ID
        val paymentEntity = paymentRepository.findByStripePaymentIntentId(paymentIntentId)
            ?: run {
                logger.warn("Payment not found for PaymentIntent: $paymentIntentId")
                return
            }
        
        val payment = paymentEntity.toDomain()
        
        // Idempotency check: if already failed, skip
        if (payment.state == PaymentState.FAILED) {
            logger.info("Payment ${payment.id} already failed, skipping webhook processing")
            return
        }
        
        // Transition to FAILED
        try {
            paymentService.transitionPayment(payment.id, PaymentState.FAILED)
            logger.info("Payment ${payment.id} transitioned to FAILED (PaymentIntent: $paymentIntentId)")
        } catch (e: Exception) {
            logger.error("Failed to transition payment ${payment.id} to FAILED", e)
        }
    }
    
    /**
     * Handles payment_intent.amount_capturable_updated event.
     * 
     * This means the payment was authorized (funds are on hold, ready to capture).
     * Transitions payment to AUTHORIZED state and triggers capture.
     */
    private fun handlePaymentIntentAuthorized(event: Event) {
        val paymentIntent = event.dataObjectDeserializer.deserializeUnsafe() as PaymentIntent
        val paymentIntentId = paymentIntent.id
        
        logger.info("PaymentIntent authorized (amount capturable): $paymentIntentId")
        
        // Find payment by Stripe PaymentIntent ID
        val paymentEntity = paymentRepository.findByStripePaymentIntentId(paymentIntentId)
            ?: run {
                logger.warn("Payment not found for PaymentIntent: $paymentIntentId")
                return
            }
        
        val payment = paymentEntity.toDomain()
        
        // Idempotency check: if already authorized, skip
        if (payment.state == PaymentState.AUTHORIZED) {
            logger.info("Payment ${payment.id} already authorized, skipping webhook processing")
            return
        }
        
        // Transition to AUTHORIZED
        try {
            paymentService.transitionPayment(payment.id, PaymentState.AUTHORIZED)
            logger.info("Payment ${payment.id} transitioned to AUTHORIZED (PaymentIntent: $paymentIntentId)")
            
            // Trigger capture (manual capture mode)
            // Publish CapturePaymentCommand to Kafka
            val captureCommand = CapturePaymentCommand(
                paymentId = payment.id,
                idempotencyKey = payment.idempotencyKey,
                attempt = 1
            )
            kafkaProducer.publishCommand(captureCommand)
            logger.info("Published CapturePaymentCommand for payment ${payment.id} after authorization")
        } catch (e: Exception) {
            logger.error("Failed to transition payment ${payment.id} to AUTHORIZED", e)
        }
    }
}

