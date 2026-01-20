package com.payments.platform.inventory.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.payments.platform.inventory.service.ReservationService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class CleanupCommandConsumer(
    private val objectMapper: ObjectMapper,
    private val reservationService: ReservationService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["inventory.commands"],
        groupId = "inventory-service-cleanup",
        containerFactory = "byteArrayKafkaListenerContainerFactory"
    )
    fun consumeCleanupCommands(record: ConsumerRecord<String, ByteArray>, ack: Acknowledgment) {
        val commandKey = record.key()
        logger.info("Received Cleanup Command: topic=${record.topic()}, key=$commandKey, offset=${record.offset()}")

        try {
            // Parse JSON payload
            val commandMap = objectMapper.readValue<Map<String, Any>>(record.value())
            val commandType = commandMap["type"] as? String

            logger.info("Processing cleanup command type: $commandType")

            when (commandType) {
                "CLEANUP_EXPIRED_RESERVATIONS" -> {
                    logger.info("Processing cleanup of expired reservations")
                    val result = reservationService.cleanupExpiredReservations()
                    logger.info("Cleanup completed: ${result.expiredCount} reservations expired, ${result.releasedCount} reservations released")
                }
                else -> {
                    logger.warn("Unknown or unhandled cleanup command type: $commandType")
                }
            }
            ack.acknowledge()
        } catch (e: Exception) {
            logger.error("Error processing cleanup command: ${e.message}", e)
            // Acknowledge to prevent reprocessing of problematic message
            // In production, you might want to send to DLQ instead
            ack.acknowledge()
        }
    }
}
