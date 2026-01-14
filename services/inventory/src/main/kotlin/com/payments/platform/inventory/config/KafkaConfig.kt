package com.payments.platform.inventory.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.*
import org.springframework.kafka.support.serializer.JsonSerializer
import java.time.Duration
import java.util.concurrent.TimeUnit

@Configuration
class KafkaConfig(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
    @Value("\${kafka.topics.events}") private val eventsTopic: String,
    @Value("\${kafka.topics.commands}") private val commandsTopic: String,
    @Value("\${kafka.topics.catalog-events:catalog.events}") private val catalogEventsTopic: String
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
    fun kafkaAdmin(): KafkaAdmin {
        val configs = mapOf(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers
        )
        return KafkaAdmin(configs)
    }

    @Bean
    fun byteArrayProducerFactory(): ProducerFactory<String, ByteArray> {
        val configs = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to org.apache.kafka.common.serialization.ByteArraySerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.RETRIES_CONFIG to 3,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true
        )
        return DefaultKafkaProducerFactory<String, ByteArray>(configs)
    }

    @Bean
    fun kafkaTemplate(byteArrayProducerFactory: ProducerFactory<String, ByteArray>): KafkaTemplate<String, ByteArray> {
        return KafkaTemplate(byteArrayProducerFactory)
    }

    @Bean
    fun jsonProducerFactory(objectMapper: ObjectMapper): ProducerFactory<String, Any> {
        val configs = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.RETRIES_CONFIG to 3,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true
        )
        val factory = DefaultKafkaProducerFactory<String, Any>(configs)
        factory.setValueSerializer(JsonSerializer(objectMapper))
        return factory
    }

    @Bean
    fun jsonKafkaTemplate(jsonProducerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> {
        return KafkaTemplate(jsonProducerFactory)
    }

    @Bean
    fun byteArrayConsumerFactory(): ConsumerFactory<String, ByteArray> {
        val configs = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "inventory-service",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false
        )
        return DefaultKafkaConsumerFactory<String, ByteArray>(configs)
    }

    @Bean
    fun byteArrayKafkaListenerContainerFactory(
        byteArrayConsumerFactory: ConsumerFactory<String, ByteArray>
    ): ConcurrentKafkaListenerContainerFactory<String, ByteArray> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, ByteArray>()
        factory.consumerFactory = byteArrayConsumerFactory
        factory.setConcurrency(3)
        factory.containerProperties.ackMode = org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE
        return factory
    }

    @Bean
    fun registerTopics(kafkaAdmin: KafkaAdmin): CommandLineRunner {
        return CommandLineRunner {
            logger.info("Registering Kafka topics idempotently...")
            
            val adminClient = AdminClient.create(kafkaAdmin.configurationProperties)
            
            try {
                val topics = listOf(
                    NewTopic(eventsTopic, 3, 1.toShort()),
                    NewTopic(commandsTopic, 3, 1.toShort()),
                    NewTopic(catalogEventsTopic, 3, 1.toShort())
                )
                
                val createTopicsResult = adminClient.createTopics(topics)
                createTopicsResult.all().get(30, TimeUnit.SECONDS)
                
                logger.info("Successfully registered Kafka topics: $eventsTopic, $commandsTopic, $catalogEventsTopic")
            } catch (e: Exception) {
                if (e.message?.contains("already exists") == true || 
                    e.cause?.message?.contains("already exists") == true) {
                    logger.info("Kafka topics already exist, skipping creation: $eventsTopic, $commandsTopic, $catalogEventsTopic")
                } else {
                    logger.warn("Failed to register Kafka topics (may already exist): ${e.message}")
                }
            } finally {
                adminClient.close(Duration.ofSeconds(5))
            }
        }
    }
}

