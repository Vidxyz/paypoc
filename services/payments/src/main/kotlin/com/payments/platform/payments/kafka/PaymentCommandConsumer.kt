package com.payments.platform.payments.kafka

import com.payments.platform.payments.domain.Payment
import com.payments.platform.payments.domain.PaymentState
import com.payments.platform.payments.persistence.PaymentRepository
import com.payments.platform.payments.service.PaymentService
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
 * - AuthorizePayment: Process payment authorization
 * - CapturePayment: Process payment capture
 * - RetryPaymentStep: Retry a failed payment step
 */
@Component
class PaymentCommandConsumer(
    private val paymentRepository: PaymentRepository,
    private val paymentService: PaymentService,
    private val kafkaProducer: PaymentKafkaProducer
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
     * Flow:
     * 1. Check payment state (idempotency)
     * 2. Transition to CONFIRMING if in CREATED state
     * 3. Execute authorization (simulate for now)
     * 4. Emit event (success or failure)
     * 5. Advance state to AUTHORIZED or FAILED
     */
    private fun handleAuthorizePayment(command: AuthorizePaymentCommand) {
        var paymentEntity = paymentRepository.findById(command.paymentId)
            .orElseThrow { IllegalArgumentException("Payment not found: ${command.paymentId}") }
        
        var payment = paymentEntity.toDomain()
        
        // Idempotency check: if already authorized or failed, skip
        if (payment.state == PaymentState.AUTHORIZED || payment.state == PaymentState.FAILED) {
            logger.info("Payment ${command.paymentId} already processed (state: ${payment.state}), skipping authorization")
            return
        }
        
        // If in CREATED state, transition to CONFIRMING first
        if (payment.state == PaymentState.CREATED) {
            paymentService.transitionPayment(payment.id, PaymentState.CONFIRMING)
            logger.info("Payment ${command.paymentId} transitioned to CONFIRMING state")
            
            // Reload payment to get updated state
            paymentEntity = paymentRepository.findById(command.paymentId)
                .orElseThrow { IllegalArgumentException("Payment not found: ${command.paymentId}") }
            payment = paymentEntity.toDomain()
        }
        
        // Ensure we're in CONFIRMING state before proceeding
        if (payment.state != PaymentState.CONFIRMING) {
            logger.warn("Payment ${command.paymentId} is in unexpected state ${payment.state} for authorization. Expected CONFIRMING.")
            return
        }
        
        // Execute authorization (simulate for now - later will call Stripe)
        val success = simulateAuthorization(payment.id)
        
        if (success) {
            // Advance state to AUTHORIZED
            paymentService.transitionPayment(payment.id, PaymentState.AUTHORIZED)
            
            // Emit success event
            val event = PaymentAuthorizedEvent(
                paymentId = payment.id,
                idempotencyKey = payment.idempotencyKey,
                attempt = command.attempt
            )
            kafkaProducer.publishEvent(event)
            
            logger.info("Payment ${payment.id} authorized successfully")
        } else {
            // Handle failure
            handleAuthorizationFailure(command, payment)
        }
    }

    /**
     * Handle CapturePayment command.
     * For now, just log - will implement later.
     */
    private fun handleCapturePayment(command: CapturePaymentCommand) {
        logger.info("CapturePayment not yet implemented for payment ${command.paymentId}")
        // TODO: Implement capture logic
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

    /**
     * Simulate authorization (for now).
     * Later this will call Stripe APIs.
     */
    private fun simulateAuthorization(paymentId: UUID): Boolean {
        // Simulate 90% success rate for testing
        return Math.random() > 0.1
    }

    /**
     * Handle authorization failure.
     * Either retry or mark payment as failed.
     */
    private fun handleAuthorizationFailure(
        command: AuthorizePaymentCommand,
        payment: com.payments.platform.payments.domain.Payment
    ) {
        if (command.attempt < MAX_RETRY_ATTEMPTS) {
            // Retry with incremented attempt
            val retryCommand = RetryPaymentStepCommand(
                paymentId = command.paymentId,
                idempotencyKey = command.idempotencyKey,
                originalType = "AUTHORIZE_PAYMENT",
                attempt = command.attempt + 1
            )
            
            // Publish to retry topic (will be processed with delay)
            kafkaProducer.publishRetry(retryCommand)
            
            logger.info("Scheduling retry for payment ${command.paymentId} (attempt ${command.attempt + 1}/${MAX_RETRY_ATTEMPTS})")
        } else {
            // Max retries exceeded - mark as failed
            val event = PaymentFailedEvent(
                paymentId = payment.id,
                idempotencyKey = payment.idempotencyKey,
                reason = "Authorization failed after ${MAX_RETRY_ATTEMPTS} attempts",
                attempt = command.attempt
            )
            kafkaProducer.publishEvent(event)
            
            paymentService.transitionPayment(payment.id, PaymentState.FAILED)
            
            logger.warn("Payment ${payment.id} failed after ${MAX_RETRY_ATTEMPTS} attempts")
        }
    }
}

