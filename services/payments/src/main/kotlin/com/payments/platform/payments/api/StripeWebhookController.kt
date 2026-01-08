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
import com.stripe.model.Transfer
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
    private val refundRepository: com.payments.platform.payments.persistence.RefundRepository,
    private val payoutService: com.payments.platform.payments.service.PayoutService
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
                "transfer.created" -> handleTransferCreated(event)
                "transfer.paid" -> handleTransferPaid(event)
                "transfer.failed" -> handleTransferFailed(event)
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
    
    /**
     * Handles transfer.created event.
     * 
     * This means a transfer was initiated with Stripe.
     * We track this but don't process until transfer.paid (money hasn't moved yet).
     */
    private fun handleTransferCreated(event: Event) {
        val stripeTransfer = event.dataObjectDeserializer.deserializeUnsafe() as Transfer
        val stripeTransferId = stripeTransfer.id
        
        logger.info("Transfer created: $stripeTransferId (amount: ${stripeTransfer.amount}, currency: ${stripeTransfer.currency})")
        
        // Find payout by Stripe Transfer ID
        val payout = payoutService.getPayoutByStripeTransferId(stripeTransferId)
        if (payout == null) {
            logger.warn("Payout not found for Stripe Transfer ID: $stripeTransferId")
            return
        }
        
        // Transfer created - payout should already be in PROCESSING state
        // Just log for tracking
        logger.info("Payout ${payout.id} has transfer $stripeTransferId created (state: ${payout.state})")
    }
    
    /**
     * Handles transfer.paid event.
     * 
     * This means the transfer completed successfully and money is in seller's account.
     * Transitions payout to COMPLETED and publishes PayoutCompletedEvent to trigger ledger write.
     */
    private fun handleTransferPaid(event: Event) {
        val stripeTransfer = event.dataObjectDeserializer.deserializeUnsafe() as Transfer
        val stripeTransferId = stripeTransfer.id
        
        logger.info("Transfer paid: $stripeTransferId (amount: ${stripeTransfer.amount}, currency: ${stripeTransfer.currency})")
        
        // Find payout by Stripe Transfer ID
        val payout = payoutService.getPayoutByStripeTransferId(stripeTransferId)
            ?: run {
                logger.warn("Payout not found for Stripe Transfer ID: $stripeTransferId")
                return
            }
        
        // Idempotency check: if already completed, skip
        if (payout.state == com.payments.platform.payments.domain.PayoutState.COMPLETED) {
            logger.info("Payout ${payout.id} already completed, skipping webhook processing")
            return
        }
        
        // Validate state: must be PROCESSING
        if (payout.state != com.payments.platform.payments.domain.PayoutState.PROCESSING) {
            logger.warn(
                "Payout ${payout.id} is in state ${payout.state}, " +
                "cannot complete payout. Expected PROCESSING. Stripe Transfer ID: $stripeTransferId"
            )
            // Transition to FAILED if in unexpected state
            try {
                payoutService.transitionPayout(
                    payout.id,
                    com.payments.platform.payments.domain.PayoutState.FAILED,
                    failureReason = "Unexpected state: ${payout.state}"
                )
            } catch (e: Exception) {
                logger.error("Failed to transition payout ${payout.id} to FAILED", e)
            }
            return
        }
        
        // Transition payout to COMPLETED
        try {
            val completedAt = Instant.now()
            payoutService.transitionPayout(
                payout.id,
                com.payments.platform.payments.domain.PayoutState.COMPLETED,
                completedAt = completedAt
            )
            logger.info("Payout ${payout.id} transitioned to COMPLETED (Stripe Transfer ID: $stripeTransferId)")
            
            // Publish PayoutCompletedEvent to Kafka
            // This will trigger the ledger write
            val payoutCompletedEvent = com.payments.platform.payments.kafka.PayoutCompletedEvent(
                payoutId = payout.id,
                paymentId = payout.id,  // Use payoutId as paymentId for Kafka key
                idempotencyKey = payout.idempotencyKey,
                sellerId = payout.sellerId,
                amountCents = payout.amountCents,
                currency = payout.currency,
                stripeTransferId = stripeTransferId,
                attempt = 1,
                createdAt = Instant.now(),
                payload = emptyMap()
            )
            kafkaProducer.publishPayoutCompletedEvent(payoutCompletedEvent)
            
            logger.info("Published PayoutCompletedEvent for payout ${payout.id} (Stripe Transfer ID: $stripeTransferId)")
        } catch (e: Exception) {
            logger.error("Failed to process payout ${payout.id}", e)
        }
    }
    
    /**
     * Handles transfer.failed event.
     * 
     * This means the transfer failed (e.g., seller account closed, insufficient funds).
     * Transitions payout to FAILED state.
     */
    private fun handleTransferFailed(event: Event) {
        val stripeTransfer = event.dataObjectDeserializer.deserializeUnsafe() as Transfer
        val stripeTransferId = stripeTransfer.id
        // Stripe Transfer failure reason - use a generic message
        // The actual failure reason can be retrieved from Stripe API if needed
        val failureReason = "Transfer failed (Stripe Transfer ID: $stripeTransferId)"
        
        logger.warn("Transfer failed: $stripeTransferId, reason: $failureReason")
        
        // Find payout by Stripe Transfer ID
        val payout = payoutService.getPayoutByStripeTransferId(stripeTransferId)
            ?: run {
                logger.warn("Payout not found for Stripe Transfer ID: $stripeTransferId")
                return
            }
        
        // Idempotency check: if already failed or completed, skip
        if (payout.state == com.payments.platform.payments.domain.PayoutState.FAILED ||
            payout.state == com.payments.platform.payments.domain.PayoutState.COMPLETED) {
            logger.info("Payout ${payout.id} already in terminal state ${payout.state}, skipping webhook processing")
            return
        }
        
        // Transition payout to FAILED
        try {
            payoutService.transitionPayout(
                payout.id,
                com.payments.platform.payments.domain.PayoutState.FAILED,
                failureReason = failureReason
            )
            logger.info("Payout ${payout.id} transitioned to FAILED (Stripe Transfer ID: $stripeTransferId, reason: $failureReason)")
        } catch (e: Exception) {
            logger.error("Failed to transition payout ${payout.id} to FAILED", e)
        }
    }
}

