package com.payments.platform.ledger.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.util.backoff.FixedBackOff
import org.apache.kafka.clients.consumer.ConsumerRecord

/**
 * Kafka configuration for Ledger Service.
 * 
 * Consumes generic messages from payment.events topic as Map<String, Any>.
 * The consumer will filter by the "type" field to only process PAYMENT_CAPTURED events.
 */
@Configuration
class KafkaConfig(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
    @Value("\${kafka.topics.events:payment.events}") private val eventsTopic: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    @Bean
    fun objectMapper(): ObjectMapper {
        return jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }

    @Bean
    fun consumerFactory(): ConsumerFactory<String, ByteArray> {
        // Use ByteArrayDeserializer to completely bypass Spring Kafka's type resolution
        // We'll manually deserialize to Map<String, Any> in the consumer
        // This avoids any issues with type headers pointing to classes that don't exist
        val configs = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "ledger-service",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false
        )
        return DefaultKafkaConsumerFactory<String, ByteArray>(configs)
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, ByteArray>
    ): ConcurrentKafkaListenerContainerFactory<String, ByteArray> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, ByteArray>()
        factory.consumerFactory = consumerFactory
        factory.setConcurrency(3)
        factory.containerProperties.ackMode = org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE
        
        // Error handler for deserialization errors
        // This will skip messages that can't be deserialized to JSON
        val recoverer = ConsumerRecordRecoverer { record: ConsumerRecord<*, *>, exception: Exception ->
            logger.warn(
                "Skipping message at offset ${record.offset()} in partition ${record.partition()} " +
                "due to deserialization error: ${exception.message}"
            )
        }
        val errorHandler = DefaultErrorHandler(
            recoverer,
            FixedBackOff(0, 0) // No retries, skip immediately
        )
        factory.setCommonErrorHandler(errorHandler)
        
        return factory
    }
}