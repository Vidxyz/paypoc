package com.payments.platform.ledger.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.payments.platform.ledger.kafka.PaymentCapturedEvent
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.support.serializer.JsonDeserializer

/**
 * Kafka configuration for Ledger Service.
 * 
 * Consumes PaymentCapturedEvent from payment.events topic.
 */
@Configuration
class KafkaConfig(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
    @Value("\${kafka.topics.events:payment.events}") private val eventsTopic: String
) {
    
    @Bean
    fun objectMapper(): ObjectMapper {
        return jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }

    @Bean
    fun consumerFactory(objectMapper: ObjectMapper): ConsumerFactory<String, PaymentCapturedEvent> {
        val configs = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "ledger-service",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to JsonDeserializer::class.java,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false, // Manual commit for better control
            JsonDeserializer.VALUE_DEFAULT_TYPE to PaymentCapturedEvent::class.java,
            JsonDeserializer.TRUSTED_PACKAGES to "*" // Allow deserialization of our message types
        )
        val factory = DefaultKafkaConsumerFactory<String, PaymentCapturedEvent>(configs)
        factory.setValueDeserializer(JsonDeserializer(PaymentCapturedEvent::class.java, objectMapper))
        return factory
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, PaymentCapturedEvent>
    ): ConcurrentKafkaListenerContainerFactory<String, PaymentCapturedEvent> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, PaymentCapturedEvent>()
        factory.consumerFactory = consumerFactory
        factory.setConcurrency(3) // Process 3 messages concurrently
        factory.containerProperties.ackMode = org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE
        return factory
    }
}

