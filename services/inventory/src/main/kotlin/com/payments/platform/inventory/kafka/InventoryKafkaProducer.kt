package com.payments.platform.inventory.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class InventoryKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
    @Value("\${kafka.topics.events}") private val eventsTopic: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    fun publishStockCreatedEvent(event: StockCreatedEvent) {
        val message = buildMessage(event, "StockCreatedEvent", event.productId.toString())
        kafkaTemplate.send(message)
        logger.info("Published StockCreatedEvent for product ${event.productId}")
    }
    
    fun publishStockUpdatedEvent(event: StockUpdatedEvent) {
        val message = buildMessage(event, "StockUpdatedEvent", event.productId.toString())
        kafkaTemplate.send(message)
        logger.info("Published StockUpdatedEvent for product ${event.productId}")
    }
    
    fun publishReservationCreatedEvent(event: ReservationCreatedEvent) {
        val message = buildMessage(event, "ReservationCreatedEvent", event.orderId.toString())
        kafkaTemplate.send(message)
        logger.info("Published ReservationCreatedEvent for order ${event.orderId}, product ${event.productId}")
    }
    
    fun publishReservationConfirmedEvent(event: ReservationConfirmedEvent) {
        val message = buildMessage(event, "ReservationConfirmedEvent", event.orderId.toString())
        kafkaTemplate.send(message)
        logger.info("Published ReservationConfirmedEvent for order ${event.orderId}, product ${event.productId}")
    }
    
    fun publishReservationCancelledEvent(event: ReservationCancelledEvent) {
        val message = buildMessage(event, "ReservationCancelledEvent", event.orderId.toString())
        kafkaTemplate.send(message)
        logger.info("Published ReservationCancelledEvent for order ${event.orderId}, product ${event.productId}")
    }
    
    fun publishReservationFulfilledEvent(event: ReservationFulfilledEvent) {
        val message = buildMessage(event, "ReservationFulfilledEvent", event.orderId.toString())
        kafkaTemplate.send(message)
        logger.info("Published ReservationFulfilledEvent for order ${event.orderId}, product ${event.productId}")
    }
    
    private fun buildMessage(event: Any, eventType: String, key: String): Message<Any> {
        return MessageBuilder
            .withPayload(event)
            .setHeader(KafkaHeaders.TOPIC, eventsTopic)
            .setHeader(KafkaHeaders.KEY, key)
            .setHeader("type", eventType)
            .setHeader("eventId", UUID.randomUUID().toString())  // Changed from "id" to "eventId" (id is read-only)
            .setHeader("eventTimestamp", Instant.now().toString())  // Changed from "timestamp" to "eventTimestamp" (timestamp is read-only)
            .build()
    }
}

// Kafka Event DTOs
data class StockCreatedEvent(
    val stockId: UUID,
    val productId: UUID,
    val quantity: Int
)

data class StockUpdatedEvent(
    val stockId: UUID,
    val productId: UUID,
    val newQuantity: Int
)

data class ReservationCreatedEvent(
    val reservationId: UUID,
    val productId: UUID,
    val orderId: UUID,
    val quantity: Int,
    val expiresAt: Instant
)

data class ReservationConfirmedEvent(
    val reservationId: UUID,
    val orderId: UUID,
    val productId: UUID,
    val quantity: Int
)

data class ReservationCancelledEvent(
    val reservationId: UUID,
    val orderId: UUID,
    val productId: UUID,
    val quantity: Int
)

data class ReservationFulfilledEvent(
    val reservationId: UUID,
    val orderId: UUID,
    val productId: UUID,
    val quantity: Int
)
