package com.payments.platform.payments.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule.Builder
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.payments.platform.payments.kafka.PaymentMessage
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.kafka.support.serializer.JsonSerializer
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Kafka configuration with JSON serialization.
 * 
 * Topics are registered idempotently on application startup.
 * If topics already exist, registration is a no-op.
 */
@Configuration
class KafkaConfig(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
    @Value("\${kafka.topics.commands}") private val commandsTopic: String,
    @Value("\${kafka.topics.events}") private val eventsTopic: String,
    @Value("\${kafka.topics.retry}") private val retryTopic: String
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
    fun producerFactory(objectMapper: ObjectMapper): ProducerFactory<String, PaymentMessage> {
        val configs = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all", // Wait for all replicas
            ProducerConfig.RETRIES_CONFIG to 3,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true // Prevent duplicate messages
        )
        val factory = DefaultKafkaProducerFactory<String, PaymentMessage>(configs)
        factory.setValueSerializer(JsonSerializer(objectMapper))
        return factory
    }

    @Bean
    fun kafkaTemplate(producerFactory: ProducerFactory<String, PaymentMessage>): KafkaTemplate<String, PaymentMessage> {
        return KafkaTemplate(producerFactory)
    }

    @Bean
    fun genericProducerFactory(objectMapper: ObjectMapper): ProducerFactory<String, Any> {
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
    fun genericKafkaTemplate(genericProducerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> {
        return KafkaTemplate(genericProducerFactory)
    }

    @Bean
    fun consumerFactory(objectMapper: ObjectMapper): ConsumerFactory<String, PaymentMessage> {
        val configs = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "payments-service",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to JsonDeserializer::class.java,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false, // Manual commit for better control
            JsonDeserializer.VALUE_DEFAULT_TYPE to PaymentMessage::class.java,
            JsonDeserializer.TRUSTED_PACKAGES to "*" // Allow deserialization of our message types
        )
        val factory = DefaultKafkaConsumerFactory<String, PaymentMessage>(configs)
        factory.setValueDeserializer(JsonDeserializer(PaymentMessage::class.java, objectMapper))
        return factory
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, PaymentMessage>
    ): ConcurrentKafkaListenerContainerFactory<String, PaymentMessage> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, PaymentMessage>()
        factory.consumerFactory = consumerFactory
        factory.setConcurrency(3) // Process 3 messages concurrently
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
                    NewTopic(commandsTopic, 3, 1.toShort()),  // 3 partitions, replication factor 1
                    NewTopic(eventsTopic, 3, 1.toShort()),
                    NewTopic(retryTopic, 3, 1.toShort())
                )
                
                val createTopicsResult = adminClient.createTopics(topics)
                
                // Wait for topics to be created (or verify they already exist)
                createTopicsResult.all().get(30, TimeUnit.SECONDS)
                
                logger.info("Successfully registered Kafka topics: $commandsTopic, $eventsTopic, $retryTopic")
            } catch (e: Exception) {
                // Check if topics already exist (this is expected and fine)
                if (e.message?.contains("already exists") == true || 
                    e.cause?.message?.contains("already exists") == true) {
                    logger.info("Kafka topics already exist, skipping creation: $commandsTopic, $eventsTopic, $retryTopic")
                } else {
                    logger.warn("Failed to register Kafka topics (may already exist): ${e.message}")
                    // Don't fail startup if topics can't be created - they might already exist
                    // In production, ensure topics are created via infrastructure as code
                }
            } finally {
                adminClient.close(Duration.ofSeconds(5))
            }
        }
    }
}

