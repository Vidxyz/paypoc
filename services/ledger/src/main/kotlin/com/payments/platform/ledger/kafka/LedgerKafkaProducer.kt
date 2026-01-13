package com.payments.platform.ledger.kafka

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.SendResult
import org.springframework.stereotype.Service
import java.util.*

/**
 * Kafka producer for publishing ledger events.
 */
@Service
class LedgerKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, LedgerTransactionCreatedEvent>,
    @Value("\${kafka.topics.events:payment.events}") private val eventsTopic: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Publish a LedgerTransactionCreatedEvent to the payment.events topic.
     * The payment ID, refund ID, or chargeback ID is used as the Kafka key for partitioning.
     */
    fun publishLedgerTransactionCreated(event: LedgerTransactionCreatedEvent) {
        try {
            // Use paymentId, refundId, or chargebackId as the Kafka key for partitioning
            val key = when {
                event.paymentId != null -> event.paymentId.toString()
                event.refundId != null -> event.refundId.toString()
                event.chargebackId != null -> event.chargebackId.toString()
                else -> event.ledgerTransactionId.toString() // Fallback to transaction ID
            }
            
            val result = kafkaTemplate.send(eventsTopic, key, event)
            result.whenComplete { result: SendResult<String, LedgerTransactionCreatedEvent>?, exception: Throwable? ->
                if (exception != null) {
                    val entityId = event.paymentId ?: event.refundId ?: event.chargebackId ?: event.ledgerTransactionId
                    logger.error("Failed to publish LedgerTransactionCreatedEvent for entity $entityId", exception)
                } else if (result != null) {
                    val recordMetadata = result.recordMetadata
                    val entityId = event.paymentId ?: event.refundId ?: event.chargebackId ?: event.ledgerTransactionId
                    logger.info(
                        "Published LedgerTransactionCreatedEvent for entity $entityId " +
                        "(ledger transaction: ${event.ledgerTransactionId}) to partition ${recordMetadata.partition()}"
                    )
                }
            }
        } catch (e: Exception) {
            val entityId = event.paymentId ?: event.refundId ?: event.chargebackId ?: event.ledgerTransactionId
            logger.error("Error publishing LedgerTransactionCreatedEvent for entity $entityId", e)
            throw e
        }
    }
}

/**
 * Kafka producer configuration for Ledger Service.
 */
@Configuration
class LedgerKafkaProducerConfig(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String
) {
    @Bean
    fun producerFactory(): ProducerFactory<String, LedgerTransactionCreatedEvent> {
        val configs = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to org.springframework.kafka.support.serializer.JsonSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all", // Wait for all replicas to acknowledge
            ProducerConfig.RETRIES_CONFIG to 3,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true // Prevent duplicate messages
        )
        return DefaultKafkaProducerFactory<String, LedgerTransactionCreatedEvent>(configs)
    }

    @Bean
    fun kafkaTemplate(
        producerFactory: ProducerFactory<String, LedgerTransactionCreatedEvent>
    ): KafkaTemplate<String, LedgerTransactionCreatedEvent> {
        return KafkaTemplate(producerFactory)
    }
}

