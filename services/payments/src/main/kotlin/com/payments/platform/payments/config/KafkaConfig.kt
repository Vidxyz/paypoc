package com.payments.platform.payments.config

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaAdmin
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Kafka configuration and topic registration.
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
    fun kafkaAdmin(): KafkaAdmin {
        val configs = mapOf(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers
        )
        return KafkaAdmin(configs)
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

