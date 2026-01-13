package com.payments.platform.payments.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.payments.platform.payments.persistence.SellerStripeAccountEntity
import com.payments.platform.payments.persistence.SellerStripeAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Consumer for user.created events.
 * 
 * When a SELLER account is created, this consumer creates an entry in seller_stripe_accounts
 * with stripe_account_id initially NULL. The seller can later provide their Stripe account ID
 * via the seller console.
 * 
 * BUYER accounts are ignored - only sellers need Stripe account entries.
 */
@Component
class UserCreatedEventConsumer(
    private val sellerStripeAccountRepository: SellerStripeAccountRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["user.events"],
        groupId = "payments-service",
        containerFactory = "byteArrayKafkaListenerContainerFactory"
    )
    @Transactional
    fun handleUserCreatedEvent(
        @Payload payload: ByteArray,
        @Header(KafkaHeaders.RECEIVED_KEY) key: String?,
        acknowledgment: Acknowledgment
    ) {
        logger.info("=== UserCreatedEventConsumer.handleUserCreatedEvent CALLED ===")
        logger.debug("Received message with key: $key, payload size: ${payload.size} bytes")

        try {
            // Manually deserialize the byte array to Map<String, Any>
            val messageString = String(payload)
            logger.debug("Message content: $messageString")
            
            val message: Map<String, Any> = try {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue<Map<String, Any>>(payload)
            } catch (e: Exception) {
                logger.error("Failed to deserialize message to Map: ${e.message}", e)
                logger.error("Message content: $messageString")
                acknowledgment.acknowledge() // Acknowledge to skip this message
                return
            }
            
            // Extract event type
            val eventType = message["eventType"] as? String
            if (eventType != "user.created") {
                logger.debug("Ignoring event type: $eventType")
                acknowledgment.acknowledge()
                return
            }

            // Extract event fields (using camelCase as per JSON standard)
            val userId = UUID.fromString(message["userId"] as? String ?: throw IllegalArgumentException("Missing userId in message"))
            val email = message["email"] as? String ?: throw IllegalArgumentException("Missing email in message")
            val accountType = message["accountType"] as? String ?: "BUYER"
            val currency = "CAD" // Default currency, can be configurable in the future

            logger.info("Received user.created event for user $userId (email: $email, account_type: $accountType)")

            // Only create seller_stripe_accounts entry for SELLER accounts
            if (accountType != "SELLER") {
                logger.info("User $userId has account_type=$accountType. Only SELLER accounts get seller_stripe_accounts entries. Skipping.")
                acknowledgment.acknowledge()
                return
            }

            // Use email as seller_id (as per requirement: seller_id = email)
            val sellerId = email

            // Check if entry already exists (idempotency)
            val existingAccount = sellerStripeAccountRepository.findBySellerIdAndCurrency(sellerId, currency)

            if (existingAccount != null) {
                logger.info("seller_stripe_accounts entry already exists for seller $sellerId (currency: $currency). Skipping creation.")
                acknowledgment.acknowledge()
                return
            }

            // Create seller_stripe_accounts entry with NULL stripe_account_id
            val entity = SellerStripeAccountEntity(
                sellerId = sellerId,
                currency = currency,
                stripeAccountId = null, // NULL initially, seller will provide later
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
            
            sellerStripeAccountRepository.save(entity)

            logger.info("Successfully created seller_stripe_accounts entry for seller $sellerId (email: $email, currency: $currency) with NULL stripe_account_id")

            acknowledgment.acknowledge()

        } catch (e: Exception) {
            logger.error("Failed to process user.created event: ${e.message}", e)
            // Don't acknowledge - message will be retried
            throw e
        }
    }
}

