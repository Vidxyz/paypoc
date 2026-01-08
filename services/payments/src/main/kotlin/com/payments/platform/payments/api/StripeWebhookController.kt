package com.payments.platform.payments.api

import com.payments.platform.payments.domain.PaymentState
import com.payments.platform.payments.kafka.CapturePaymentCommand
import com.payments.platform.payments.kafka.PaymentKafkaProducer
import com.payments.platform.payments.persistence.PaymentRepository
import com.payments.platform.payments.service.PaymentService
import com.payments.platform.payments.stripe.StripeService
import com.stripe.model.Event
import com.stripe.model.PaymentIntent
import com.stripe.model.Refund
import com.stripe.net.Webhook
import io.swagger.v3.oas.annotations.Hidden
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

/**
 * Webhook controller for Stripe events.
 * 
 * Handles:
 * - payment_intent.succeeded: Payment was captured successfully
 * - payment_intent.payment_failed: Payment authorization failed
 * - payment_intent.amount_capturable_updated: Payment was authorized (ready to capture)
 * - refund.updated: Refund status changed (process when status is "succeeded")
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
    private val kafkaProducer: PaymentKafkaProducer,
    private val refundService: com.payments.platform.payments.service.RefundService,
    private val refundRepository: com.payments.platform.payments.persistence.RefundRepository
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
                "refund.updated" -> handleRefundUpdated(event)
                else -> {
                    logger.info("Unhandled webhook event type: ${event.type}")
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
     * 
     * Handles the case where payment might be in CREATED state by transitioning through CONFIRMING first.
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
        
        try {
            // Handle state transition: if in CREATED, transition through CONFIRMING first
            when (payment.state) {
                PaymentState.CREATED -> {
                    // Transition through intermediate state
                    paymentService.transitionPayment(payment.id, PaymentState.CONFIRMING)
                    logger.info("Payment ${payment.id} transitioned from CREATED to CONFIRMING")
                    // Now transition to AUTHORIZED
                    paymentService.transitionPayment(payment.id, PaymentState.AUTHORIZED)
                    logger.info("Payment ${payment.id} transitioned from CONFIRMING to AUTHORIZED")
                }
                PaymentState.CONFIRMING -> {
                    // Direct transition to AUTHORIZED
                    paymentService.transitionPayment(payment.id, PaymentState.AUTHORIZED)
                    logger.info("Payment ${payment.id} transitioned from CONFIRMING to AUTHORIZED")
                }
                else -> {
                    logger.warn(
                        "Payment ${payment.id} is in state ${payment.state}, " +
                        "cannot transition to AUTHORIZED. Expected CREATED or CONFIRMING. PaymentIntent: $paymentIntentId"
                    )
                    return
                }
            }
            
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
            logger.error("Failed to transition payment ${payment.id} to AUTHORIZED: ${e.message}", e)
        }
    }
    
    /**
     * Handles refund.updated event.
     * 
     * This means the refund status changed. We only process when status is "succeeded".
     * Transitions refund to REFUNDED state and publishes RefundCompletedEvent to trigger ledger write.
     */
    private fun handleRefundUpdated(event: Event) {
        val stripeRefund = event.dataObjectDeserializer.deserializeUnsafe() as Refund
        val stripeRefundId = stripeRefund.id
        val refundStatus = stripeRefund.status
        
        logger.info("Refund updated: $stripeRefundId, status: $refundStatus")
        
        // Only process when refund status is "succeeded"
        if (refundStatus != "succeeded") {
            logger.debug("Refund $stripeRefundId status is $refundStatus, not processing (only process 'succeeded' status)")
            return
        }
        
        // Find refund by Stripe Refund ID
        val refundEntity = refundRepository.findByStripeRefundId(stripeRefundId)
            ?: run {
                logger.warn("Refund not found for Stripe Refund ID: $stripeRefundId")
                return
            }
        
        val refund = refundEntity.toDomain()
        
        // Idempotency check: if already refunded, skip
        if (refund.state == com.payments.platform.payments.domain.RefundState.REFUNDED) {
            logger.info("Refund ${refund.id} already refunded, skipping webhook processing")
            return
        }
        
        // Validate state: must be REFUNDING
        if (refund.state != com.payments.platform.payments.domain.RefundState.REFUNDING) {
            logger.warn(
                "Refund ${refund.id} is in state ${refund.state}, " +
                "cannot complete refund. Expected REFUNDING. Stripe Refund ID: $stripeRefundId"
            )
            // Transition to FAILED if in unexpected state
            try {
                refundService.transitionRefund(refund.id, com.payments.platform.payments.domain.RefundState.FAILED)
            } catch (e: Exception) {
                logger.error("Failed to transition refund ${refund.id} to FAILED", e)
            }
            return
        }
        
        // Transition refund to REFUNDED
        try {
            refundService.transitionRefund(refund.id, com.payments.platform.payments.domain.RefundState.REFUNDED)
            logger.info("Refund ${refund.id} transitioned to REFUNDED (Stripe Refund ID: $stripeRefundId)")
            
            // Get payment details for the event
            val paymentEntity = paymentRepository.findById(refund.paymentId).orElse(null)
            if (paymentEntity == null) {
                logger.error("Payment ${refund.paymentId} not found for refund ${refund.id}")
                return
            }
            val payment = paymentEntity.toDomain()
            
            // Transition payment from REFUNDING to REFUNDED and set refundedAt timestamp
            try {
                val refundedAt = Instant.now()
                paymentService.transitionPaymentWithRefundedAt(refund.paymentId, PaymentState.REFUNDED, refundedAt)
                logger.info("Payment ${refund.paymentId} transitioned to REFUNDED state with refundedAt=$refundedAt")
            } catch (e: Exception) {
                logger.error("Failed to transition payment ${refund.paymentId} to REFUNDED state", e)
                // Don't fail the refund processing - refund is complete, payment state can be corrected
            }
            
            // Publish RefundCompletedEvent to Kafka
            // This will trigger the ledger write
            val refundCompletedEvent = com.payments.platform.payments.kafka.RefundCompletedEvent(
                refundId = refund.id,
                paymentId = refund.paymentId,
                refundAmountCents = refund.refundAmountCents,
                platformFeeRefundCents = refund.platformFeeRefundCents,
                netSellerRefundCents = refund.netSellerRefundCents,
                currency = refund.currency,
                stripeRefundId = stripeRefundId,
                stripePaymentIntentId = payment.stripePaymentIntentId ?: "",
                idempotencyKey = refund.idempotencyKey,
                buyerId = payment.buyerId,
                sellerId = payment.sellerId
            )
            kafkaProducer.publishRefundCompletedEvent(refundCompletedEvent)
            
            logger.info("Published RefundCompletedEvent for refund ${refund.id} (Stripe Refund ID: $stripeRefundId)")
        } catch (e: Exception) {
            logger.error("Failed to process refund ${refund.id}", e)
        }
    }
}

