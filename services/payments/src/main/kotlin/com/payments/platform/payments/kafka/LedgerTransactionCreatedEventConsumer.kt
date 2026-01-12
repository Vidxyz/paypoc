package com.payments.platform.payments.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.payments.platform.payments.service.PaymentService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Consumer for LedgerTransactionCreatedEvent from payment.events topic.
 * 
 * This consumer updates the payment record with the ledger_transaction_id
 * after the ledger service has created the transaction.
 * 
 * Flow:
 * 1. Ledger service creates transaction → publishes LedgerTransactionCreatedEvent
 * 2. This consumer receives the event
 * 3. Updates payment record with ledger_transaction_id
 */
@Component
class LedgerTransactionCreatedEventConsumer(
    private val paymentService: PaymentService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${kafka.topics.events}"],
        groupId = "payments-service-ledger-events",
        containerFactory = "byteArrayKafkaListenerContainerFactory"
    )
    @Transactional
    fun handleLedgerTransactionCreatedEvent(
        @Payload payload: ByteArray,
        @Header(KafkaHeaders.RECEIVED_KEY) key: String?,
        acknowledgment: Acknowledgment
    ) {
        logger.info("=== LedgerTransactionCreatedEventConsumer.handleLedgerTransactionCreatedEvent CALLED ===")
        logger.debug("Received message with key: $key, payload size: ${payload.size} bytes")

        try {
            // Deserialize the byte array to Map<String, Any> first
            val messageString = String(payload)
            logger.debug("Message content: $messageString")
            
            val message: Map<String, Any> = try {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue<Map<String, Any>>(payload)
            } catch (e: Exception) {
                logger.error("Failed to deserialize message to Map: ${e.message}", e)
                logger.error("Message content: $messageString")
                acknowledgment.acknowledge() // Acknowledge to skip this message
                return
            }
            
            // Check if this is a LedgerTransactionCreatedEvent
            // We'll identify it by checking if it has ledgerTransactionId field
            val ledgerTransactionId = message["ledgerTransactionId"] as? String
            val paymentId = message["paymentId"] as? String
            val idempotencyKey = message["idempotencyKey"] as? String
            
            if (ledgerTransactionId == null || paymentId == null || idempotencyKey == null) {
                // Not a LedgerTransactionCreatedEvent, skip it
                logger.debug("Skipping message - not a LedgerTransactionCreatedEvent (missing required fields)")
                acknowledgment.acknowledge()
                return
            }
            
            // Check if this event is for a payment (has paymentId)
            // Other events might have ledgerTransactionId but not paymentId
            if (paymentId.isBlank()) {
                logger.debug("Skipping message - not a payment-related LedgerTransactionCreatedEvent")
                acknowledgment.acknowledge()
                return
            }
            
            logger.info(
                "Received LedgerTransactionCreatedEvent for payment $paymentId " +
                "(ledger transaction: $ledgerTransactionId, idempotency: $idempotencyKey)"
            )
            
            // Update payment record with ledger_transaction_id
            val payment = paymentService.updateLedgerTransactionId(
                paymentId = java.util.UUID.fromString(paymentId),
                ledgerTransactionId = java.util.UUID.fromString(ledgerTransactionId)
            )
            
            logger.info(
                "Updated payment ${payment.id} with ledger transaction ID: ${payment.ledgerTransactionId}"
            )
            
            // Commit offset only after successful processing
            acknowledgment.acknowledge()
        } catch (e: Exception) {
            logger.error("Error processing LedgerTransactionCreatedEvent: ${e.message}", e)
            // Don't acknowledge - message will be retried by Kafka
            throw e
        }
    }
}

