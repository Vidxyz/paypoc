package com.payments.platform.payments.kafka

import com.payments.platform.payments.domain.PaymentState
import com.payments.platform.payments.persistence.PaymentRepository
import com.payments.platform.payments.service.PaymentService
import com.payments.platform.payments.stripe.StripeService
import com.payments.platform.payments.stripe.StripeServiceException
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Consumer for payment.commands topic.
 * 
 * Handles:
 * - AuthorizePayment: Trigger manual capture after authorization (for manual capture mode)
 * - CapturePayment: Process payment capture and publish PaymentCapturedEvent
 * - RetryPaymentStep: Retry a failed payment step
 */
@Component
class PaymentCommandConsumer(
    private val paymentRepository: PaymentRepository,
    private val paymentService: PaymentService,
    private val kafkaProducer: PaymentKafkaProducer,
    private val stripeService: StripeService
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    private val MAX_RETRY_ATTEMPTS = 5

    @KafkaListener(
        topics = ["\${kafka.topics.commands}"],
        groupId = "payments-service",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    fun handleCommand(
        message: PaymentMessage,
        acknowledgment: Acknowledgment
    ) {
        try {
            logger.info("Received command: ${message.type} for payment ${message.paymentId} (attempt ${message.attempt})")
            
            when (message) {
                is AuthorizePaymentCommand -> handleAuthorizePayment(message)
                is CapturePaymentCommand -> handleCapturePayment(message)
                is RetryPaymentStepCommand -> handleRetryPaymentStep(message)
                else -> {
                    logger.warn("Unknown command type: ${message.type} for payment ${message.paymentId}")
                }
            }
            
            // Commit offset only after successful processing
            acknowledgment.acknowledge()
        } catch (e: Exception) {
            logger.error("Error processing command ${message.type} for payment ${message.paymentId}", e)
            // Don't acknowledge - message will be retried by Kafka
            throw e
        }
    }

    /**
     * Handle AuthorizePayment command.
     * 
     * This command is published after payment creation, but the actual authorization
     * happens when the client confirms payment with Stripe using client_secret.
     * 
     * Flow:
     * 1. Client confirms with Stripe → Stripe webhook: payment_intent.amount_capturable_updated
     * 2. Webhook handler transitions payment to AUTHORIZED
     * 3. This command then triggers manual capture (since capture_method is manual)
     * 
     * Note: In automatic capture mode, this command would not be needed.
     */
    private fun handleAuthorizePayment(command: AuthorizePaymentCommand) {
        val paymentEntity = paymentRepository.findById(command.paymentId)
            .orElseThrow { IllegalArgumentException("Payment not found: ${command.paymentId}") }
        
        val payment = paymentEntity.toDomain()
        
        // Idempotency check: if already authorized or failed, skip
        if (payment.state == PaymentState.AUTHORIZED || payment.state == PaymentState.FAILED) {
            logger.info("Payment ${command.paymentId} already processed (state: ${payment.state}), skipping authorization")
            return
        }
        
        // If in CREATED state, transition to CONFIRMING
        if (payment.state == PaymentState.CREATED) {
            paymentService.transitionPayment(payment.id, PaymentState.CONFIRMING)
            logger.info("Payment ${command.paymentId} transitioned to CONFIRMING state")
        }
        
        // Wait for webhook to transition to AUTHORIZED
        // The webhook handler (payment_intent.amount_capturable_updated) will transition to AUTHORIZED
        // Once AUTHORIZED, we can trigger capture
        logger.info("Waiting for Stripe webhook to authorize payment ${command.paymentId}")
    }

    /**
     * Handle CapturePayment command.
     * 
     * This is called after payment is AUTHORIZED (via webhook).
     * 
     * Flow:
     * 1. Validate payment state (must be AUTHORIZED)
     * 2. Call Stripe API to capture PaymentIntent (manual capture)
     * 3. Stripe will send webhook: payment_intent.succeeded
     * 4. Webhook handler will publish this command again (idempotent)
     * 5. This handler publishes PaymentCapturedEvent (triggers ledger write)
     * 
     * Note: The webhook handler also publishes this command when payment_intent.succeeded
     * is received, ensuring idempotency.
     */
    private fun handleCapturePayment(command: CapturePaymentCommand) {
        val paymentEntity = paymentRepository.findById(command.paymentId)
            .orElseThrow { IllegalArgumentException("Payment not found: ${command.paymentId}") }
        
        val payment = paymentEntity.toDomain()
        
        // Idempotency check: if already captured, skip
        if (payment.state == PaymentState.CAPTURED) {
            logger.info("Payment ${command.paymentId} already captured, skipping")
            return
        }
        
        // Validate state: must be AUTHORIZED
        if (payment.state != PaymentState.AUTHORIZED) {
            logger.warn("Payment ${command.paymentId} is in state ${payment.state}, cannot capture. Expected AUTHORIZED.")
            paymentService.transitionPayment(payment.id, PaymentState.FAILED)
            kafkaProducer.publishEvent(
                PaymentFailedEvent(
                    paymentId = payment.id,
                    idempotencyKey = payment.idempotencyKey,
                    reason = "Cannot capture payment in state ${payment.state}",
                    attempt = command.attempt
                )
            )
            return
        }
        
        // If PaymentIntent exists and hasn't been captured yet, call Stripe to capture
        if (payment.stripePaymentIntentId != null) {
            try {
                val paymentIntent = stripeService.getPaymentIntent(payment.stripePaymentIntentId!!)
                
                // Only capture if not already captured
                if (paymentIntent.status != "succeeded") {
                    logger.info("Capturing Stripe PaymentIntent ${payment.stripePaymentIntentId} for payment ${payment.id}")
                    if (paymentIntent.status == "requires_capture") {
                        // Call Stripe capture API
                        stripeService.capturePaymentIntent(payment.stripePaymentIntentId!!)
                        logger.info("Stripe PaymentIntent ${payment.stripePaymentIntentId} capture initiated")
                        // Note: Stripe will send webhook payment_intent.succeeded after capture
                        // The webhook handler will also publish CapturePaymentCommand (idempotent)
                        // So we return here and let the webhook complete the flow
                        return
                    } else {
                        logger.warn("PaymentIntent ${payment.stripePaymentIntentId} is in status ${paymentIntent.status}, cannot capture")
                        return
                    }
                } else {
                    logger.info("PaymentIntent ${payment.stripePaymentIntentId} already succeeded, proceeding to publish event")
                }
            } catch (e: StripeServiceException) {
                logger.error("Failed to capture Stripe PaymentIntent for payment ${payment.id}: ${e.message}", e)
                // Don't fail - webhook will retry
                throw e
            }
        }
        
        // Transition to CAPTURED (this happens after Stripe confirms via webhook)
        // But we also handle the case where webhook already confirmed
        paymentService.transitionPayment(payment.id, PaymentState.CAPTURED)
        
        // Publish PaymentCapturedEvent - this triggers ledger write
        val sellerBreakdownEvents = payment.sellerBreakdown.map { breakdown ->
            SellerBreakdownEvent(
                sellerId = breakdown.sellerId,
                sellerGrossAmountCents = breakdown.sellerGrossAmountCents,
                platformFeeCents = breakdown.platformFeeCents,
                netSellerAmountCents = breakdown.netSellerAmountCents
            )
        }
        
        val capturedEvent = PaymentCapturedEvent(
            paymentId = payment.id,
            idempotencyKey = payment.idempotencyKey,
            orderId = payment.orderId,
            buyerId = payment.buyerId,
            grossAmountCents = payment.grossAmountCents,
            platformFeeCents = payment.platformFeeCents,
            netSellerAmountCents = payment.netSellerAmountCents,
            currency = payment.currency,
            stripePaymentIntentId = payment.stripePaymentIntentId ?: throw IllegalStateException("Payment ${payment.id} missing stripePaymentIntentId"),
            sellerBreakdown = sellerBreakdownEvents,
            attempt = command.attempt
        )
        kafkaProducer.publishEvent(capturedEvent)
        
        logger.info("Payment ${payment.id} captured successfully, PaymentCapturedEvent published")
    }

    /**
     * Handle RetryPaymentStep command.
     * Re-executes the original command type with incremented attempt.
     */
    private fun handleRetryPaymentStep(command: RetryPaymentStepCommand) {
        logger.info("Retrying payment step ${command.originalType} for payment ${command.paymentId} (attempt ${command.attempt})")
        
        // Re-create the original command with incremented attempt
        when (command.originalType) {
            "AUTHORIZE_PAYMENT" -> {
                val retryCommand = AuthorizePaymentCommand(
                    paymentId = command.paymentId,
                    idempotencyKey = command.idempotencyKey,
                    attempt = command.attempt
                )
                handleAuthorizePayment(retryCommand)
            }
            "CAPTURE_PAYMENT" -> {
                val retryCommand = CapturePaymentCommand(
                    paymentId = command.paymentId,
                    idempotencyKey = command.idempotencyKey,
                    attempt = command.attempt
                )
                handleCapturePayment(retryCommand)
            }
            else -> {
                logger.warn("Unknown retry command type: ${command.originalType} for payment ${command.paymentId}")
            }
        }
    }
}
