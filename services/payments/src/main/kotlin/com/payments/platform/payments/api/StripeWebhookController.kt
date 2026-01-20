package com.payments.platform.payments.api

import com.payments.platform.payments.domain.ChargebackOutcome
import com.payments.platform.payments.domain.ChargebackState
import com.payments.platform.payments.domain.PaymentState
import com.payments.platform.payments.domain.PayoutState
import com.payments.platform.payments.domain.RefundState
import com.payments.platform.payments.kafka.CapturePaymentCommand
import com.payments.platform.payments.kafka.ChargebackCreatedEvent
import com.payments.platform.payments.kafka.ChargebackLostEvent
import com.payments.platform.payments.kafka.ChargebackWarningClosedEvent
import com.payments.platform.payments.kafka.ChargebackWonEvent
import com.payments.platform.payments.kafka.PaymentFailedEvent
import com.payments.platform.payments.kafka.PaymentKafkaProducer
import com.payments.platform.payments.kafka.PayoutCompletedEvent
import com.payments.platform.payments.kafka.RefundCompletedEvent
import com.payments.platform.payments.kafka.SellerRefundBreakdownEvent
import com.payments.platform.payments.persistence.PaymentRepository
import com.payments.platform.payments.persistence.RefundRepository
import com.payments.platform.payments.service.ChargebackService
import com.payments.platform.payments.service.PaymentService
import com.payments.platform.payments.service.PayoutService
import com.payments.platform.payments.service.RefundService
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
    private val refundService: RefundService,
    private val refundRepository: RefundRepository,
    private val payoutService: PayoutService,
    private val chargebackService: ChargebackService
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
                "charge.dispute.created" -> handleDisputeCreated(event)
                "charge.dispute.updated" -> handleDisputeUpdated(event)
                "charge.dispute.closed" -> handleDisputeClosed(event)
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
            
            // Publish PaymentFailedEvent so other services (e.g., order service) can handle it
            kafkaProducer.publishEvent(
                PaymentFailedEvent(
                    paymentId = payment.id,
                    idempotencyKey = payment.idempotencyKey,
                    reason = "PaymentIntent failed: $paymentIntentId",
                    attempt = 1
                )
            )
            logger.info("Published PaymentFailedEvent for payment ${payment.id}")
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
        if (refund.state == RefundState.REFUNDED) {
            logger.info("Refund ${refund.id} already refunded, skipping webhook processing")
            return
        }
        
        // Validate state: must be REFUNDING
        if (refund.state != RefundState.REFUNDING) {
            logger.warn(
                "Refund ${refund.id} is in state ${refund.state}, " +
                "cannot complete refund. Expected REFUNDING. Stripe Refund ID: $stripeRefundId"
            )
            // Transition to FAILED if in unexpected state
            try {
                refundService.transitionRefund(refund.id, RefundState.FAILED)
            } catch (e: Exception) {
                logger.error("Failed to transition refund ${refund.id} to FAILED", e)
            }
            return
        }
        
        // Transition refund to REFUNDED
        try {
            refundService.transitionRefund(refund.id, RefundState.REFUNDED)
            logger.info("Refund ${refund.id} transitioned to REFUNDED (Stripe Refund ID: $stripeRefundId)")
            
            // Get payment details for the event
            val paymentEntity = paymentRepository.findById(refund.paymentId).orElse(null)
            if (paymentEntity == null) {
                logger.error("Payment ${refund.paymentId} not found for refund ${refund.id}")
                return
            }
            val payment = paymentEntity.toDomain()
            
            // Check if payment is fully refunded (sum of all completed refunds >= payment amount)
            val allRefunds = refundRepository.findByPaymentId(refund.paymentId)
            val completedRefunds = allRefunds.filter { it.state == RefundState.REFUNDED }
            val totalRefundedAmount = completedRefunds.sumOf { it.refundAmountCents }
            val isFullyRefunded = totalRefundedAmount >= payment.grossAmountCents
            
            // Only transition payment to REFUNDED if fully refunded
            // Otherwise, keep it in REFUNDING state to allow additional refunds
            try {
                if (isFullyRefunded) {
                    val refundedAt = Instant.now()
                    paymentService.transitionPaymentWithRefundedAt(refund.paymentId, PaymentState.REFUNDED, refundedAt)
                    logger.info(
                        "Payment ${refund.paymentId} transitioned to REFUNDED state with refundedAt=$refundedAt " +
                        "(total refunded: $totalRefundedAmount, payment amount: ${payment.grossAmountCents})"
                    )
                } else {
                    logger.info(
                        "Payment ${refund.paymentId} remains in REFUNDING state " +
                        "(partial refund: total refunded: $totalRefundedAmount, payment amount: ${payment.grossAmountCents})"
                    )
                }
            } catch (e: Exception) {
                logger.error("Failed to transition payment ${refund.paymentId} state", e)
                // Don't fail the refund processing - refund is complete, payment state can be corrected
            }
            
            // Get seller refund breakdown from refund entity (stored when refund was created)
            // This ensures accurate breakdown based on actual order items refunded, not proportional
            val sellerRefundBreakdown = if (refund.sellerRefundBreakdown != null && refund.sellerRefundBreakdown.isNotEmpty()) {
                // Use stored breakdown (accurate for partial refunds based on order items)
                refund.sellerRefundBreakdown.map { seller ->
                    SellerRefundBreakdownEvent(
                        sellerId = seller.sellerId,
                        refundAmountCents = seller.refundAmountCents,
                        platformFeeRefundCents = seller.platformFeeRefundCents,
                        netSellerRefundCents = seller.netSellerRefundCents
                    )
                }
            } else {
                // Fallback: use original seller breakdown (should only happen for old refunds without breakdown)
                // This is a fallback for refunds created before sellerRefundBreakdown was added
                payment.sellerBreakdown.map { seller ->
                    SellerRefundBreakdownEvent(
                        sellerId = seller.sellerId,
                        refundAmountCents = seller.sellerGrossAmountCents,
                        platformFeeRefundCents = seller.platformFeeCents,
                        netSellerRefundCents = seller.netSellerAmountCents
                    )
                }
            }
            
            // Convert orderItemsRefunded from domain to event format
            val orderItemsRefunded = refund.orderItemsRefunded?.map { item ->
                com.payments.platform.payments.kafka.OrderItemRefundInfo(
                    orderItemId = item.orderItemId,
                    quantity = item.quantity,
                    sellerId = item.sellerId,
                    priceCents = item.priceCents
                )
            }
            
            // Publish RefundCompletedEvent to Kafka
            // This will trigger the ledger write and order service refund tracking
            val refundCompletedEvent = RefundCompletedEvent(
                refundId = refund.id,
                paymentId = refund.paymentId,
                orderId = payment.orderId,
                refundAmountCents = refund.refundAmountCents,
                platformFeeRefundCents = refund.platformFeeRefundCents,
                netSellerRefundCents = refund.netSellerRefundCents,
                currency = refund.currency,
                stripeRefundId = stripeRefundId,
                stripePaymentIntentId = payment.stripePaymentIntentId ?: "",
                idempotencyKey = refund.idempotencyKey,
                buyerId = payment.buyerId,
                sellerRefundBreakdown = sellerRefundBreakdown,
                orderItemsRefunded = orderItemsRefunded
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
     * This means a transfer was initiated with Stripe and money has been debited from our account.
     * We write to the ledger immediately because the money has left our control.
     * 
     * Note: In test mode, transfer.paid may never fire automatically, but the money has already
     * left our account when the transfer is created. In production, transfer.paid fires when
     * money arrives at the destination (can take days), but accounting should reflect when
     * money leaves our control, not when it arrives.
     */
    private fun handleTransferCreated(event: Event) {
        val stripeTransfer = event.dataObjectDeserializer.deserializeUnsafe() as Transfer
        val stripeTransferId = stripeTransfer.id
        
        logger.info("Transfer created: $stripeTransferId (amount: ${stripeTransfer.amount}, currency: ${stripeTransfer.currency})")
        
        // Find payout - try by transfer ID first (in case it's already been updated)
        // If not found, try by payout ID from metadata (payout is flushed before transfer creation, so it should exist)
        var payout = payoutService.getPayoutByStripeTransferId(stripeTransferId)
        
        if (payout == null) {
            // Fallback: Get payout ID from Stripe Transfer metadata
            // The payout is flushed before creating the transfer, so it should exist in the database
            val payoutIdStr = stripeTransfer.metadata?.get("payoutId")
            if (payoutIdStr != null) {
                try {
                    val payoutId = UUID.fromString(payoutIdStr)
                    payout = try {
                        payoutService.getPayout(payoutId)
                    } catch (e: IllegalArgumentException) {
                        logger.warn("Payout $payoutId from metadata not found: ${e.message}")
                        null
                    }
                    if (payout != null) {
                        logger.info("Found payout ${payout.id} by ID from metadata (transfer ID: $stripeTransferId)")
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to parse payout ID from metadata: $payoutIdStr", e)
                }
            }
        }
        
        if (payout == null) {
            logger.error(
                "Payout not found for Stripe Transfer ID: $stripeTransferId. " +
                "Metadata: ${stripeTransfer.metadata}. Webhook will be ignored."
            )
            return
        }
        
        
        // Idempotency check: if already completed, skip (ledger already written)
        if (payout.state == PayoutState.COMPLETED) {
            logger.info("Payout ${payout.id} already completed, skipping webhook processing")
            return
        }
        
        // Validate state: must be PROCESSING
        if (payout.state != PayoutState.PROCESSING) {
            logger.warn(
                "Payout ${payout.id} is in state ${payout.state}, " +
                "cannot process transfer.created. Expected PROCESSING. Stripe Transfer ID: $stripeTransferId"
            )
            return
        }
        
        // Money has left our account - write to ledger immediately
        // This reduces SELLER_PAYABLE liability and STRIPE_CLEARING asset
        try {
            // Publish PayoutCompletedEvent to Kafka
            // This will trigger the ledger write
            val payoutCompletedEvent = PayoutCompletedEvent(
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
            
            logger.info(
                "Published PayoutCompletedEvent for payout ${payout.id} " +
                "(Stripe Transfer ID: $stripeTransferId) - money has left our account"
            )
            
            // Transition payout to COMPLETED
            // Note: In test mode, transfer.paid may never fire, so we complete it here
            // In production, transfer.paid will fire later as confirmation
            val completedAt = Instant.now()
            payoutService.transitionPayout(
                payout.id,
                PayoutState.COMPLETED,
                completedAt = completedAt
            )
            logger.info("Payout ${payout.id} transitioned to COMPLETED (Stripe Transfer ID: $stripeTransferId)")
        } catch (e: Exception) {
            logger.error("Failed to process transfer.created for payout ${payout.id}", e)
        }
    }
    
    /**
     * Handles transfer.paid event.
     * 
     * This means the transfer completed successfully and money has arrived in seller's account.
     * This is a confirmation event - the ledger was already written on transfer.created
     * when the money left our account.
     * 
     * In test mode, this event may never fire automatically, but that's OK because
     * we already wrote to the ledger on transfer.created.
     */
    private fun handleTransferPaid(event: Event) {
        val stripeTransfer = event.dataObjectDeserializer.deserializeUnsafe() as Transfer
        val stripeTransferId = stripeTransfer.id
        
        logger.info("Transfer paid: $stripeTransferId (amount: ${stripeTransfer.amount}, currency: ${stripeTransfer.currency})")
        
        // Find payout - try by transfer ID first, then by payout ID from metadata
        var payout = payoutService.getPayoutByStripeTransferId(stripeTransferId)
        
        val payoutIdStr = stripeTransfer.metadata?.get("payoutId")
        if (payout == null && payoutIdStr != null) {
            try {
                val payoutId = UUID.fromString(payoutIdStr)
                payout = try {
                    payoutService.getPayout(payoutId)
                } catch (e: IllegalArgumentException) {
                    null
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        if (payout == null) {
            logger.warn("Payout not found for Stripe Transfer ID: $stripeTransferId")
            return
        }
        
        // Idempotency check: if already completed, skip
        // (Ledger was already written on transfer.created)
        if (payout.state == PayoutState.COMPLETED) {
            logger.info(
                "Payout ${payout.id} already completed (ledger written on transfer.created), " +
                "transfer.paid is just confirmation"
            )
            return
        }
        
        // If payout is still in PROCESSING, it means transfer.created webhook was missed
        // Complete it now (ledger write will happen via idempotency)
        if (payout.state == PayoutState.PROCESSING) {
            logger.warn(
                "Payout ${payout.id} still in PROCESSING when transfer.paid received. " +
                "transfer.created webhook may have been missed. Completing payout now."
            )
            try {
                val completedAt = Instant.now()
                payoutService.transitionPayout(
                    payout.id,
                    PayoutState.COMPLETED,
                    completedAt = completedAt
                )
                
                // Publish PayoutCompletedEvent to Kafka (idempotency will prevent duplicate ledger writes)
                val payoutCompletedEvent = PayoutCompletedEvent(
                    payoutId = payout.id,
                    paymentId = payout.id,
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
                
                logger.info("Payout ${payout.id} completed via transfer.paid (transfer.created was missed)")
            } catch (e: Exception) {
                logger.error("Failed to complete payout ${payout.id} via transfer.paid", e)
            }
        } else {
            logger.warn(
                "Payout ${payout.id} is in unexpected state ${payout.state} when transfer.paid received. " +
                "Stripe Transfer ID: $stripeTransferId"
            )
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
        
        // Find payout - try by transfer ID first, then by payout ID from metadata
        var payout = payoutService.getPayoutByStripeTransferId(stripeTransferId)
        
        val payoutIdStr = stripeTransfer.metadata?.get("payoutId")
        if (payout == null && payoutIdStr != null) {
            try {
                val payoutId = UUID.fromString(payoutIdStr)
                payout = try {
                    payoutService.getPayout(payoutId)
                } catch (e: IllegalArgumentException) {
                    null
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        if (payout == null) {
            logger.warn("Payout not found for Stripe Transfer ID: $stripeTransferId")
            return
        }
        
        // Idempotency check: if already failed or completed, skip
        if (payout.state == PayoutState.FAILED ||
            payout.state == PayoutState.COMPLETED) {
            logger.info("Payout ${payout.id} already in terminal state ${payout.state}, skipping webhook processing")
            return
        }
        
        // Transition payout to FAILED
        try {
            payoutService.transitionPayout(
                payout.id,
                PayoutState.FAILED,
                failureReason = failureReason
            )
            logger.info("Payout ${payout.id} transitioned to FAILED (Stripe Transfer ID: $stripeTransferId, reason: $failureReason)")
        } catch (e: Exception) {
            logger.error("Failed to transition payout ${payout.id} to FAILED", e)
        }
    }
    
    /**
     * Handles charge.dispute.created event.
     * 
     * This means a chargeback (dispute) was initiated by the buyer's bank.
     * Money is immediately debited from STRIPE_CLEARING.
     * 
     * Flow:
     * 1. Find payment by Stripe Charge ID (from dispute)
     * 2. Create chargeback record
     * 3. Publish ChargebackCreatedEvent to trigger ledger write
     * 4. Transition chargeback to appropriate state
     */
    private fun handleDisputeCreated(event: Event) {
        val stripeDispute = event.dataObjectDeserializer.deserializeUnsafe() as com.stripe.model.Dispute
        val stripeDisputeId = stripeDispute.id
        val stripeChargeId = stripeDispute.charge
        
        logger.info("Dispute created: $stripeDisputeId (Stripe Charge ID: $stripeChargeId, status: ${stripeDispute.status})")
        
        // Find payment by Stripe Charge ID
        // We need to get the PaymentIntent ID from the charge
        val paymentEntity = try {
            // Retrieve the charge to get the payment intent ID
            val charge = com.stripe.model.Charge.retrieve(stripeChargeId)
            val paymentIntentId = charge.paymentIntent as? String
                ?: run {
                    logger.warn("Charge $stripeChargeId has no PaymentIntent ID")
                    return
                }
            
            paymentRepository.findByStripePaymentIntentId(paymentIntentId)
        } catch (e: Exception) {
            logger.error("Failed to find payment for dispute $stripeDisputeId (Charge ID: $stripeChargeId)", e)
            return
        }
        
        if (paymentEntity == null) {
            logger.warn("Payment not found for Stripe Charge ID: $stripeChargeId (Dispute ID: $stripeDisputeId)")
            return
        }
        
        val payment = paymentEntity.toDomain()
        
        // Idempotency check: if chargeback already exists, skip
        val existingChargeback = chargebackService.getChargebackByStripeDisputeId(stripeDisputeId)
        if (existingChargeback != null) {
            logger.info("Chargeback with Stripe Dispute ID $stripeDisputeId already exists, skipping webhook processing")
            return
        }
        
        // Create chargeback record
        val chargeback = try {
            chargebackService.createChargebackFromDispute(stripeDispute, payment.id)
        } catch (e: Exception) {
            logger.error("Failed to create chargeback for dispute $stripeDisputeId", e)
            return
        }
        
        // Determine state based on Stripe dispute status
        val targetState = when (stripeDispute.status) {
            "warning_needs_response" -> ChargebackState.DISPUTE_CREATED
            "needs_response" -> ChargebackState.NEEDS_RESPONSE
            "under_review" -> ChargebackState.UNDER_REVIEW
            else -> ChargebackState.DISPUTE_CREATED
        }
        
        // Transition to appropriate state if not already there
        if (chargeback.state != targetState) {
            try {
                chargebackService.transitionChargeback(chargeback.id, targetState)
            } catch (e: Exception) {
                logger.error("Failed to transition chargeback ${chargeback.id} to $targetState", e)
            }
        }
        
        // Convert payment seller breakdown to chargeback breakdown
        val sellerBreakdown = payment.sellerBreakdown.map { seller ->
            com.payments.platform.payments.kafka.SellerChargebackBreakdown(
                sellerId = seller.sellerId,
                sellerGrossAmountCents = seller.sellerGrossAmountCents,
                platformFeeCents = seller.platformFeeCents,
                netSellerAmountCents = seller.netSellerAmountCents
            )
        }
        
        // Publish ChargebackCreatedEvent to Kafka
        // This will trigger the ledger write (money is debited immediately)
        val chargebackCreatedEvent = ChargebackCreatedEvent(
            chargebackId = chargeback.id,
            paymentId = payment.id,
            chargebackAmountCents = chargeback.chargebackAmountCents,
            disputeFeeCents = chargeback.disputeFeeCents,
            currency = chargeback.currency,
            stripeDisputeId = stripeDisputeId,
            stripeChargeId = stripeChargeId,
            stripePaymentIntentId = payment.stripePaymentIntentId ?: "",
            reason = chargeback.reason,
            buyerId = payment.buyerId,
            sellerBreakdown = sellerBreakdown,
            idempotencyKey = chargeback.idempotencyKey
        )
        kafkaProducer.publishChargebackCreatedEvent(chargebackCreatedEvent)
        
        logger.info(
            "Published ChargebackCreatedEvent for chargeback ${chargeback.id} " +
            "(Stripe Dispute ID: $stripeDisputeId) - money debited immediately"
        )
    }
    
    /**
     * Handles charge.dispute.updated event.
     * 
     * This means the dispute status changed (e.g., needs_response → under_review).
     * Updates chargeback state accordingly.
     * 
     * IMPORTANT: This method should NOT transition chargebacks that are already in a terminal state
     * (WON, LOST, WITHDRAWN, WARNING_CLOSED) because the charge.dispute.closed webhook may have
     * already processed the final state. This prevents race conditions where both events arrive
     * concurrently and the updated event overwrites the closed event's terminal state.
     */
    private fun handleDisputeUpdated(event: Event) {
        val stripeDispute = event.dataObjectDeserializer.deserializeUnsafe() as com.stripe.model.Dispute
        val stripeDisputeId = stripeDispute.id
        val disputeStatus = stripeDispute.status
        
        logger.info("Dispute updated: $stripeDisputeId, status: $disputeStatus")
        
        // Find chargeback by Stripe Dispute ID
        val chargeback = chargebackService.getChargebackByStripeDisputeId(stripeDisputeId)
            ?: run {
                logger.warn("Chargeback not found for Stripe Dispute ID: $stripeDisputeId")
                return
            }
        
        // Idempotency check: if chargeback is already in a terminal state, don't update
        // This prevents race conditions where charge.dispute.closed and charge.dispute.updated
        // arrive concurrently, and the updated event tries to overwrite the terminal state
        if (chargeback.outcome != null) {
            logger.info(
                "Chargeback ${chargeback.id} already in terminal state (outcome: ${chargeback.outcome}, " +
                "state: ${chargeback.state}). Ignoring dispute.updated event to prevent race condition. " +
                "Stripe Dispute ID: $stripeDisputeId"
            )
            return
        }
        
        // Determine target state based on Stripe dispute status
        val targetState = when (disputeStatus) {
            "warning_needs_response" -> ChargebackState.DISPUTE_CREATED
            "needs_response" -> ChargebackState.NEEDS_RESPONSE
            "under_review" -> ChargebackState.UNDER_REVIEW
            else -> {
                logger.debug("Dispute $stripeDisputeId status is $disputeStatus, not updating state")
                return
            }
        }
        
        // Transition if state changed
        if (chargeback.state != targetState) {
            try {
                chargebackService.transitionChargeback(chargeback.id, targetState)
                logger.info("Chargeback ${chargeback.id} transitioned to $targetState (Stripe Dispute ID: $stripeDisputeId)")
            } catch (e: Exception) {
                logger.error("Failed to transition chargeback ${chargeback.id} to $targetState", e)
            }
        }
    }
    
    /**
     * Handles charge.dispute.closed event.
     * 
     * This means the dispute was resolved (won, lost, or withdrawn).
     * Updates chargeback state and publishes appropriate event for ledger update.
     */
    private fun handleDisputeClosed(event: Event) {
        val stripeDispute = event.dataObjectDeserializer.deserializeUnsafe() as com.stripe.model.Dispute
        val stripeDisputeId = stripeDispute.id
        val disputeStatus = stripeDispute.status
        
        logger.info("Dispute closed: $stripeDisputeId, status: $disputeStatus")
        
        // Find chargeback by Stripe Dispute ID
        val chargeback = chargebackService.getChargebackByStripeDisputeId(stripeDisputeId)
            ?: run {
                logger.warn("Chargeback not found for Stripe Dispute ID: $stripeDisputeId")
                return
            }
        
        // Idempotency check: if already closed, skip
        if (chargeback.outcome != null) {
            logger.info("Chargeback ${chargeback.id} already closed with outcome ${chargeback.outcome}, skipping webhook processing")
            return
        }
        
        // Get payment details
        val paymentEntity = paymentRepository.findById(chargeback.paymentId).orElse(null)
        if (paymentEntity == null) {
            logger.error("Payment ${chargeback.paymentId} not found for chargeback ${chargeback.id}")
            return
        }
        val payment = paymentEntity.toDomain()
        
        // Determine outcome and target state
        val (outcome, targetState) = when (disputeStatus) {
            "won" -> {
                ChargebackOutcome.WON to
                ChargebackState.WON
            }
            "warning_closed" -> {
                // Dispute closed with warning but in merchant's favor - money is returned
                ChargebackOutcome.WARNING_CLOSED to
                ChargebackState.WARNING_CLOSED
            }
            "lost" -> {
                ChargebackOutcome.LOST to
                ChargebackState.LOST
            }
            "charge_refunded" -> {
                ChargebackOutcome.WITHDRAWN to
                ChargebackState.WITHDRAWN
            }
            else -> {
                logger.warn("Unknown dispute status for closed dispute: $disputeStatus")
                return
            }
        }
        
        // Transition chargeback to final state
        try {
            chargebackService.transitionChargeback(chargeback.id, targetState)
            logger.info("Chargeback ${chargeback.id} transitioned to $targetState (outcome: $outcome)")
            
            // Convert payment seller breakdown to chargeback breakdown
            val sellerBreakdown = payment.sellerBreakdown.map { seller ->
                com.payments.platform.payments.kafka.SellerChargebackBreakdown(
                    sellerId = seller.sellerId,
                    sellerGrossAmountCents = seller.sellerGrossAmountCents,
                    platformFeeCents = seller.platformFeeCents,
                    netSellerAmountCents = seller.netSellerAmountCents
                )
            }
            
            // For LOST chargebacks, calculate proportional chargeback breakdown per seller
            val chargebackSellerBreakdown = if (outcome == ChargebackOutcome.LOST) {
                // Calculate proportional chargeback for each seller based on their portion of the original payment
                val totalGrossAmount = payment.grossAmountCents
                if (totalGrossAmount > 0 && chargeback.chargebackAmountCents > 0) {
                    payment.sellerBreakdown.map { seller ->
                        // Calculate this seller's proportion of the original payment
                        val sellerProportion = seller.sellerGrossAmountCents.toDouble() / totalGrossAmount
                        
                        // Apply the same proportion to the chargeback
                        // The chargeback breakdown should match the original payment breakdown proportionally
                        val sellerChargebackGrossCents = (chargeback.chargebackAmountCents * sellerProportion).toLong()
                        
                        // Platform fee and net amount are also proportional
                        // Maintain the same fee percentage as the original payment
                        val feePercentage = if (seller.sellerGrossAmountCents > 0) {
                            seller.platformFeeCents.toDouble() / seller.sellerGrossAmountCents
                        } else {
                            0.0
                        }
                        val sellerChargebackPlatformFeeCents = (sellerChargebackGrossCents * feePercentage).toLong()
                        val sellerChargebackNetCents = sellerChargebackGrossCents - sellerChargebackPlatformFeeCents
                        
                        com.payments.platform.payments.kafka.SellerChargebackBreakdown(
                            sellerId = seller.sellerId,
                            sellerGrossAmountCents = sellerChargebackGrossCents,
                            platformFeeCents = sellerChargebackPlatformFeeCents,
                            netSellerAmountCents = sellerChargebackNetCents
                        )
                    }
                } else {
                    sellerBreakdown
                }
            } else {
                sellerBreakdown
            }
            
            // Publish appropriate event based on outcome
            when (outcome) {
                ChargebackOutcome.WON -> {
                    // Money is returned to STRIPE_CLEARING
                    val chargebackWonEvent = ChargebackWonEvent(
                        chargebackId = chargeback.id,
                        paymentId = payment.id,
                        chargebackAmountCents = chargeback.chargebackAmountCents,
                        currency = chargeback.currency,
                        stripeDisputeId = stripeDisputeId,
                        stripePaymentIntentId = payment.stripePaymentIntentId ?: "",
                        buyerId = payment.buyerId,
                        sellerBreakdown = sellerBreakdown,
                        idempotencyKey = chargeback.idempotencyKey
                    )
                    kafkaProducer.publishChargebackWonEvent(chargebackWonEvent)
                    logger.info("Published ChargebackWonEvent for chargeback ${chargeback.id} - money returned")
                }
                ChargebackOutcome.WARNING_CLOSED -> {
                    // Dispute closed with warning but in merchant's favor
                    // BOTH chargeback amount AND dispute fee are returned (unlike WON where only amount is returned)
                    val chargebackWarningClosedEvent = ChargebackWarningClosedEvent(
                        chargebackId = chargeback.id,
                        paymentId = payment.id,
                        chargebackAmountCents = chargeback.chargebackAmountCents,
                        disputeFeeCents = chargeback.disputeFeeCents,
                        currency = chargeback.currency,
                        stripeDisputeId = stripeDisputeId,
                        stripePaymentIntentId = payment.stripePaymentIntentId ?: "",
                        buyerId = payment.buyerId,
                        sellerBreakdown = sellerBreakdown,
                        idempotencyKey = chargeback.idempotencyKey
                    )
                    kafkaProducer.publishChargebackWarningClosedEvent(chargebackWarningClosedEvent)
                    logger.info("Published ChargebackWarningClosedEvent for chargeback ${chargeback.id} (warning_closed) - amount and fee returned")
                }
                ChargebackOutcome.LOST -> {
                    // Money is permanently debited, reduce seller liability proportionally
                    val chargebackLostEvent = ChargebackLostEvent(
                        chargebackId = chargeback.id,
                        paymentId = payment.id,
                        chargebackAmountCents = chargeback.chargebackAmountCents,
                        platformFeeCents = payment.platformFeeCents,
                        netSellerAmountCents = payment.netSellerAmountCents,
                        currency = chargeback.currency,
                        stripeDisputeId = stripeDisputeId,
                        stripePaymentIntentId = payment.stripePaymentIntentId ?: "",
                        buyerId = payment.buyerId,
                        sellerBreakdown = chargebackSellerBreakdown,
                        idempotencyKey = chargeback.idempotencyKey
                    )
                    kafkaProducer.publishChargebackLostEvent(chargebackLostEvent)
                    logger.info("Published ChargebackLostEvent for chargeback ${chargeback.id} - money permanently debited (distributed across ${chargebackSellerBreakdown.size} sellers)")
                }
                ChargebackOutcome.WITHDRAWN -> {
                    // Buyer withdrew dispute - money is returned (similar to won)
                    val chargebackWonEvent = ChargebackWonEvent(
                        chargebackId = chargeback.id,
                        paymentId = payment.id,
                        chargebackAmountCents = chargeback.chargebackAmountCents,
                        currency = chargeback.currency,
                        stripeDisputeId = stripeDisputeId,
                        stripePaymentIntentId = payment.stripePaymentIntentId ?: "",
                        buyerId = payment.buyerId,
                        sellerBreakdown = sellerBreakdown,
                        idempotencyKey = chargeback.idempotencyKey
                    )
                    kafkaProducer.publishChargebackWonEvent(chargebackWonEvent)
                    logger.info("Published ChargebackWonEvent for chargeback ${chargeback.id} (withdrawn) - money returned")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to process dispute closed for chargeback ${chargeback.id}", e)
        }
    }
}

