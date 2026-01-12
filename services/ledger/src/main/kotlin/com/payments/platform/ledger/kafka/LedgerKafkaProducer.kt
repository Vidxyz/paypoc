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
     * The payment ID is used as the Kafka key for partitioning.
     */
    fun publishLedgerTransactionCreated(event: LedgerTransactionCreatedEvent) {
        try {
            val result = kafkaTemplate.send(eventsTopic, event.paymentId.toString(), event)
            result.whenComplete { result: SendResult<String, LedgerTransactionCreatedEvent>?, exception: Throwable? ->
                if (exception != null) {
                    logger.error("Failed to publish LedgerTransactionCreatedEvent for payment ${event.paymentId}", exception)
                } else if (result != null) {
                    val recordMetadata = result.recordMetadata
                    logger.info(
                        "Published LedgerTransactionCreatedEvent for payment ${event.paymentId} " +
                        "(ledger transaction: ${event.ledgerTransactionId}) to partition ${recordMetadata.partition()}"
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error publishing LedgerTransactionCreatedEvent for payment ${event.paymentId}", e)
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

