package com.payments.platform.ledger.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.payments.platform.ledger.domain.AccountType
import com.payments.platform.ledger.service.LedgerService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Consumer for user.created events.
 * 
 * When a SELLER account is created, this consumer creates a SELLER_PAYABLE account in the ledger.
 * This account tracks money owed to sellers (money received from buyers that hasn't been paid out yet).
 * 
 * BUYER accounts do NOT get ledger accounts - buyers pay the platform, and we payout to sellers.
 * Only sellers need SELLER_PAYABLE accounts to track their earnings.
 */
@Component
class UserCreatedEventConsumer(
    private val ledgerService: LedgerService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["user.events"],
        groupId = "ledger-service",
        containerFactory = "kafkaListenerContainerFactory"
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
            // The KafkaConfig uses ByteArrayDeserializer, so we need to deserialize here
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
            val auth0UserId = message["auth0UserId"] as? String ?: throw IllegalArgumentException("Missing auth0UserId in message")
            val accountType = message["accountType"] as? String ?: "BUYER"
            val currency = "USD" // Default currency, can be configurable in the future

            logger.info("Received user.created event for user $userId (email: $email, auth0UserId: $auth0UserId, account_type: $accountType)")

            // Only create ledger account for SELLER accounts
            // BUYER accounts don't need ledger accounts (they pay the platform, we payout to sellers)
            if (accountType != "SELLER") {
                logger.info("User $userId has account_type=$accountType. Only SELLER accounts get ledger accounts. Skipping.")
                acknowledgment.acknowledge()
                return
            }

            // Create SELLER_PAYABLE account for seller
            // accountId = deterministic UUID based on account type + reference + currency
            // This ensures consistency with payment processing which uses the same pattern
            // accountType = SELLER_PAYABLE
            // referenceId = email (for lookup)
            // currency = USD (default)
            
            // Check if account already exists (idempotency)
            val existingAccount = try {
                ledgerService.findAccountByTypeAndReference(
                    accountType = AccountType.SELLER_PAYABLE,
                    referenceId = email,
                    currency = currency
                )
            } catch (e: Exception) {
                null
            }

            if (existingAccount != null) {
                logger.info("SELLER_PAYABLE account already exists for seller $userId (email: $email). Skipping creation.")
                acknowledgment.acknowledge()
                return
            }

            // Use deterministic UUID to match payment processing logic
            // This ensures the account ID is consistent across all operations
            val accountId = UUID.nameUUIDFromBytes("SELLER_PAYABLE_${email}_$currency".toByteArray())

            ledgerService.createAccount(
                accountId = accountId,
                accountType = AccountType.SELLER_PAYABLE,
                referenceId = email,
                currency = currency
            )

            logger.info("Successfully created SELLER_PAYABLE account for seller $userId (email: $email)")

            acknowledgment.acknowledge()

        } catch (e: Exception) {
            logger.error("Failed to process user.created event: ${e.message}", e)
            // Don't acknowledge - message will be retried
            throw e
        }
    }
}
