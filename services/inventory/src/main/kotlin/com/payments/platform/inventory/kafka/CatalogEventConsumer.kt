package com.payments.platform.inventory.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.payments.platform.inventory.service.InventoryService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CatalogEventConsumer(
    private val objectMapper: ObjectMapper,
    private val inventoryService: InventoryService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["catalog.events"],
        groupId = "inventory-service",
        containerFactory = "byteArrayKafkaListenerContainerFactory"
    )
    fun consumeCatalogEvents(record: ConsumerRecord<String, ByteArray>, ack: Acknowledgment) {
        val eventKey = record.key()
        logger.info("Received Catalog Event: topic=${record.topic()}, key=$eventKey, offset=${record.offset()}")

        try {
            // Parse JSON payload - catalog service puts type in payload, not headers
            val eventMap = objectMapper.readValue<Map<String, Any>>(record.value())
            val eventType = eventMap["type"] as? String
            
            logger.info("Processing Catalog event type: $eventType")

            when (eventType) {
                "PRODUCT_CREATED" -> {
                    val productId = UUID.fromString(eventMap["productId"] as String)
                    val sellerId = eventMap["sellerId"] as String
                    val sku = eventMap["sku"] as String
                    
                    logger.info("Processing ProductCreatedEvent for product ID: $productId, seller: $sellerId, sku: $sku")
                    // Initialize stock for new product, default to 0 or a configurable value
                    inventoryService.createOrUpdateStock(
                        productId = productId,
                        sellerId = sellerId,
                        sku = sku,
                        quantity = 0  // Seller will adjust stock later
                    )
                }
                "PRODUCT_UPDATED" -> {
                    val productId = UUID.fromString(eventMap["productId"] as String)
                    val sellerId = eventMap["sellerId"] as String
                    val sku = eventMap["sku"] as String
                    
                    logger.info("Processing ProductUpdatedEvent for product ID: $productId")
                    // Ensure stock entry exists (if it doesn't, create it)
                    val existingStock = inventoryService.getStockByProductId(productId)
                    if (existingStock == null) {
                        inventoryService.createOrUpdateStock(
                            productId = productId,
                            sellerId = sellerId,
                            sku = sku,
                            quantity = 0
                        )
                    }
                }
                "PRODUCT_DELETED" -> {
                    val productId = UUID.fromString(eventMap["productId"] as String)
                    logger.info("Processing ProductDeletedEvent for product ID: $productId")
                    // Optionally, handle stock cleanup or mark as unavailable
                    // For now, we'll leave the stock entry but it won't be reservable
                }
                else -> {
                    logger.warn("Unknown or unhandled Catalog event type: $eventType")
                }
            }
            ack.acknowledge()
        } catch (e: Exception) {
            logger.error("Error processing Catalog event: ${e.message}", e)
            // Depending on policy, could rethrow to trigger retry or send to DLQ
            // For now, acknowledge to prevent reprocessing of problematic message
            ack.acknowledge()
        }
    }
}
