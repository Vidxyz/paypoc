package com.payments.platform.payments.kafka

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Consumer for payment.retry topic.
 * 
 * Adds a delay before re-publishing the command to payment.commands.
 * This implements explicit retry with backoff.
 */
@Component
class PaymentRetryConsumer(
    private val kafkaProducer: PaymentKafkaProducer
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    // Exponential backoff: 2^attempt seconds (capped at 60 seconds)
    private fun calculateDelaySeconds(attempt: Int): Long {
        val delay = Math.pow(2.0, attempt.toDouble()).toLong()
        return delay.coerceAtMost(60) // Cap at 60 seconds
    }

    @KafkaListener(
        topics = ["\${kafka.topics.retry}"],
        groupId = "payments-service-retry",
        containerFactory = "kafkaListenerContainerFactory"
    )
    fun handleRetry(
        command: RetryPaymentStepCommand,
        acknowledgment: Acknowledgment
    ) {
        try {
            val delaySeconds = calculateDelaySeconds(command.attempt - 1)
            logger.info("Retry command for payment ${command.paymentId} (attempt ${command.attempt}) - waiting ${delaySeconds}s before retry")
            
            // Sleep before retrying
            Thread.sleep(TimeUnit.SECONDS.toMillis(delaySeconds))
            
            // Re-publish the original command to payment.commands
            when (command.originalType) {
                "AUTHORIZE_PAYMENT" -> {
                    val retryCommand = AuthorizePaymentCommand(
                        paymentId = command.paymentId,
                        idempotencyKey = command.idempotencyKey,
                        attempt = command.attempt
                    )
                    kafkaProducer.publishCommand(retryCommand)
                }
                "CAPTURE_PAYMENT" -> {
                    val retryCommand = CapturePaymentCommand(
                        paymentId = command.paymentId,
                        idempotencyKey = command.idempotencyKey,
                        attempt = command.attempt
                    )
                    kafkaProducer.publishCommand(retryCommand)
                }
                else -> {
                    logger.warn("Unknown retry command type: ${command.originalType} for payment ${command.paymentId}")
                }
            }
            
            logger.info("Re-published retry command for payment ${command.paymentId} (attempt ${command.attempt})")
            
            // Commit offset
            acknowledgment.acknowledge()
        } catch (e: Exception) {
            logger.error("Error processing retry command for payment ${command.paymentId}", e)
            // Don't acknowledge - message will be retried by Kafka
            throw e
        }
    }
}

