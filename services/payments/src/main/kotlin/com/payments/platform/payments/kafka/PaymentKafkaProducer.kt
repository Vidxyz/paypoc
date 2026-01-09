package com.payments.platform.payments.kafka

import org.apache.kafka.clients.producer.RecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Kafka producer for publishing payment commands and events.
 */
@Service
class PaymentKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, PaymentMessage>,
    private val genericKafkaTemplate: KafkaTemplate<String, Any>,
    @Value("\${kafka.topics.commands}") private val commandsTopic: String,
    @Value("\${kafka.topics.events}") private val eventsTopic: String,
    @Value("\${kafka.topics.retry}") private val retryTopic: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Publish a command to the payment.commands topic.
     * The payment ID is used as the Kafka key for partitioning.
     */
    fun publishCommand(command: PaymentMessage) {
        try {
            val result = kafkaTemplate.send(commandsTopic, command.paymentId.toString(), command)
            result.whenComplete { result: SendResult<String, PaymentMessage>?, exception: Throwable? ->
                if (exception != null) {
                    logger.error("Failed to publish command ${command.type} for payment ${command.paymentId}", exception)
                } else if (result != null) {
                    val recordMetadata = result.recordMetadata
                    logger.info("Published command ${command.type} for payment ${command.paymentId} to partition ${recordMetadata.partition()}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error publishing command ${command.type} for payment ${command.paymentId}", e)
            throw e
        }
    }

    /**
     * Publish an event to the payment.events topic.
     * The payment ID is used as the Kafka key for partitioning.
     */
    fun publishEvent(event: PaymentMessage) {
        try {
            val result = kafkaTemplate.send(eventsTopic, event.paymentId.toString(), event)
            result.whenComplete { result: SendResult<String, PaymentMessage>?, exception: Throwable? ->
                if (exception != null) {
                    logger.error("Failed to publish event ${event.type} for payment ${event.paymentId}", exception)
                } else if (result != null) {
                    val recordMetadata = result.recordMetadata
                    logger.info("Published event ${event.type} for payment ${event.paymentId} to partition ${recordMetadata.partition()}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error publishing event ${event.type} for payment ${event.paymentId}", e)
            throw e
        }
    }

    /**
     * Publish a retry command to the payment.retry topic.
     * The payment ID is used as the Kafka key for partitioning.
     */
    fun publishRetry(command: RetryPaymentStepCommand) {
        try {
            val result = kafkaTemplate.send(retryTopic, command.paymentId.toString(), command)
            result.whenComplete { result: SendResult<String, PaymentMessage>?, exception: Throwable? ->
                if (exception != null) {
                    logger.error("Failed to publish retry command for payment ${command.paymentId}", exception)
                } else if (result != null) {
                    val recordMetadata = result.recordMetadata
                    logger.info("Published retry command for payment ${command.paymentId} (attempt ${command.attempt}) to partition ${recordMetadata.partition()}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error publishing retry command for payment ${command.paymentId}", e)
            throw e
        }
    }
    
    /**
     * Publish a RefundCompletedEvent to the payment.events topic.
     * The refund ID is used as the Kafka key for partitioning.
     */
    fun publishRefundCompletedEvent(event: RefundCompletedEvent) {
        try {
            // Use generic KafkaTemplate to send RefundCompletedEvent (it's not a PaymentMessage)
            val result = genericKafkaTemplate.send(eventsTopic, event.refundId.toString(), event)
            result.whenComplete { result: SendResult<String, Any>?, exception: Throwable? ->
                if (exception != null) {
                    logger.error("Failed to publish RefundCompletedEvent for refund ${event.refundId}", exception)
                } else if (result != null) {
                    val recordMetadata = result.recordMetadata
                    logger.info("Published RefundCompletedEvent for refund ${event.refundId} to partition ${recordMetadata.partition()}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error publishing RefundCompletedEvent for refund ${event.refundId}", e)
            throw e
        }
    }
    
    /**
     * Publish a PayoutCompletedEvent to the payment.events topic.
     * The payout ID is used as the Kafka key for partitioning.
     */
    fun publishPayoutCompletedEvent(event: PayoutCompletedEvent) {
        try {
            // Use PaymentMessage interface - PayoutCompletedEvent implements it
            val result = kafkaTemplate.send(eventsTopic, event.payoutId.toString(), event)
            result.whenComplete { result: SendResult<String, PaymentMessage>?, exception: Throwable? ->
                if (exception != null) {
                    logger.error("Failed to publish PayoutCompletedEvent for payout ${event.payoutId}", exception)
                } else if (result != null) {
                    val recordMetadata = result.recordMetadata
                    logger.info("Published PayoutCompletedEvent for payout ${event.payoutId} to partition ${recordMetadata.partition()}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error publishing PayoutCompletedEvent for payout ${event.payoutId}", e)
            throw e
        }
    }
    
    /**
     * Publish a ChargebackCreatedEvent to the payment.events topic.
     * The chargeback ID is used as the Kafka key for partitioning.
     */
    fun publishChargebackCreatedEvent(event: ChargebackCreatedEvent) {
        try {
            // Use generic KafkaTemplate to send ChargebackCreatedEvent (it's not a PaymentMessage)
            val result = genericKafkaTemplate.send(eventsTopic, event.chargebackId.toString(), event)
            result.whenComplete { result: SendResult<String, Any>?, exception: Throwable? ->
                if (exception != null) {
                    logger.error("Failed to publish ChargebackCreatedEvent for chargeback ${event.chargebackId}", exception)
                } else if (result != null) {
                    val recordMetadata = result.recordMetadata
                    logger.info("Published ChargebackCreatedEvent for chargeback ${event.chargebackId} to partition ${recordMetadata.partition()}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error publishing ChargebackCreatedEvent for chargeback ${event.chargebackId}", e)
            throw e
        }
    }
    
    /**
     * Publish a ChargebackWonEvent to the payment.events topic.
     * The chargeback ID is used as the Kafka key for partitioning.
     */
    fun publishChargebackWonEvent(event: ChargebackWonEvent) {
        try {
            // Use generic KafkaTemplate to send ChargebackWonEvent (it's not a PaymentMessage)
            val result = genericKafkaTemplate.send(eventsTopic, event.chargebackId.toString(), event)
            result.whenComplete { result: SendResult<String, Any>?, exception: Throwable? ->
                if (exception != null) {
                    logger.error("Failed to publish ChargebackWonEvent for chargeback ${event.chargebackId}", exception)
                } else if (result != null) {
                    val recordMetadata = result.recordMetadata
                    logger.info("Published ChargebackWonEvent for chargeback ${event.chargebackId} to partition ${recordMetadata.partition()}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error publishing ChargebackWonEvent for chargeback ${event.chargebackId}", e)
            throw e
        }
    }
    
    /**
     * Publish a ChargebackLostEvent to the payment.events topic.
     * The chargeback ID is used as the Kafka key for partitioning.
     */
    fun publishChargebackLostEvent(event: ChargebackLostEvent) {
        try {
            // Use generic KafkaTemplate to send ChargebackLostEvent (it's not a PaymentMessage)
            val result = genericKafkaTemplate.send(eventsTopic, event.chargebackId.toString(), event)
            result.whenComplete { result: SendResult<String, Any>?, exception: Throwable? ->
                if (exception != null) {
                    logger.error("Failed to publish ChargebackLostEvent for chargeback ${event.chargebackId}", exception)
                } else if (result != null) {
                    val recordMetadata = result.recordMetadata
                    logger.info("Published ChargebackLostEvent for chargeback ${event.chargebackId} to partition ${recordMetadata.partition()}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error publishing ChargebackLostEvent for chargeback ${event.chargebackId}", e)
            throw e
        }
    }
    
    /**
     * Publish a ChargebackWarningClosedEvent to the payment.events topic.
     * The chargeback ID is used as the Kafka key for partitioning.
     * 
     * This event is published when a dispute is closed with warning_closed status.
     * Unlike WON, both the chargeback amount AND dispute fee are returned.
     */
    fun publishChargebackWarningClosedEvent(event: ChargebackWarningClosedEvent) {
        try {
            // Use generic KafkaTemplate to send ChargebackWarningClosedEvent (it's not a PaymentMessage)
            val result = genericKafkaTemplate.send(eventsTopic, event.chargebackId.toString(), event)
            result.whenComplete { result: SendResult<String, Any>?, exception: Throwable? ->
                if (exception != null) {
                    logger.error("Failed to publish ChargebackWarningClosedEvent for chargeback ${event.chargebackId}", exception)
                } else if (result != null) {
                    val recordMetadata = result.recordMetadata
                    logger.info("Published ChargebackWarningClosedEvent for chargeback ${event.chargebackId} to partition ${recordMetadata.partition()}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error publishing ChargebackWarningClosedEvent for chargeback ${event.chargebackId}", e)
            throw e
        }
    }
}

