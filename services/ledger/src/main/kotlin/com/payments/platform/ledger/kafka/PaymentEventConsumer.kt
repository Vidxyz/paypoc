package com.payments.platform.ledger.kafka

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Unified event router for payment.events topic.
 * 
 * This consumer receives all events from the payment.events topic and routes them
 * to the appropriate handler based on event type:
 * - PAYMENT_CAPTURED -> PaymentCapturedEventConsumer
 * - REFUND_COMPLETED -> RefundCompletedEventConsumer
 * - PAYOUT_COMPLETED -> PayoutCompletedEventConsumer
 */
@Component
class PaymentEventConsumer(
    private val objectMapper: ObjectMapper,
    private val paymentCapturedEventConsumer: PaymentCapturedEventConsumer,
    private val refundCompletedEventConsumer: RefundCompletedEventConsumer,
    private val payoutCompletedEventConsumer: PayoutCompletedEventConsumer
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    init {
        logger.info("=== PaymentEventConsumer bean created ===")
        logger.info("Consumer will listen to topic: payment.events")
        logger.info("Consumer group: ledger-service")
    }

    @KafkaListener(
        topics = ["\${kafka.topics.events:payment.events}"],
        groupId = "ledger-service",
        containerFactory = "kafkaListenerContainerFactory",
        id = "paymentEventRouter"
    )
    @Transactional
    fun handlePaymentEvent(
        messageBytes: ByteArray,
        acknowledgment: Acknowledgment
    ) {
        logger.info("=== PaymentEventConsumer.handlePaymentEvent CALLED ===")
        logger.info("Received message from Kafka (${messageBytes.size} bytes)")
        try {
            logger.info("Message content (first 500 chars): ${String(messageBytes).take(500)}")
        } catch (e: Exception) {
            logger.error("Failed to convert message bytes to string: ${e.message}", e)
        }
        
        // Manually deserialize bytes to Map<String, Any> to bypass Spring Kafka's type resolution
        val message = try {
            val typeRef = object : TypeReference<Map<String, Any>>() {}
            val deserialized = objectMapper.readValue(messageBytes, typeRef)
            logger.debug("Deserialized message: $deserialized")
            deserialized
        } catch (e: Exception) {
            logger.error("Failed to deserialize message bytes to Map: ${e.message}", e)
            logger.error("Message bytes (first 500 chars): ${String(messageBytes).take(500)}")
            // Acknowledge to skip this malformed message
            acknowledgment.acknowledge()
            return
        }
        
        // Route to appropriate handler based on event type
        val eventType = message["type"] as? String
        logger.info("Message event type: $eventType")
        
        when (eventType) {
            "PAYMENT_CAPTURED" -> {
                paymentCapturedEventConsumer.handlePaymentCapturedEvent(message, acknowledgment)
            }
            "REFUND_COMPLETED" -> {
                refundCompletedEventConsumer.handleRefundCompletedEvent(messageBytes, acknowledgment)
            }
            "PAYOUT_COMPLETED" -> {
                payoutCompletedEventConsumer.handlePayoutCompletedEvent(messageBytes, acknowledgment)
            }
            else -> {
                logger.warn("Unknown event type: $eventType. Acknowledging and skipping.")
                acknowledgment.acknowledge()
            }
        }
    }
}

