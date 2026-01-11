package com.payments.platform.ledger.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.payments.platform.ledger.domain.AccountType
import com.payments.platform.ledger.service.LedgerService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
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
        message: Map<String, Any>,
        acknowledgment: Acknowledgment
    ) {
        logger.info("=== UserCreatedEventConsumer.handleUserCreatedEvent CALLED ===")

        try {
            // Extract event type
            val eventType = message["event_type"] as? String
            if (eventType != "user.created") {
                logger.debug("Ignoring event type: $eventType")
                acknowledgment.acknowledge()
                return
            }

            // Deserialize event
            val userId = UUID.fromString(message["user_id"] as String)
            val email = message["email"] as String
            val auth0UserId = message["auth0_user_id"] as String
            val accountType = message["account_type"] as? String ?: "BUYER"
            val currency = "USD" // Default currency, can be configurable in the future

            logger.info("Received user.created event for user $userId (email: $email, account_type: $accountType)")

            // Only create ledger account for SELLER accounts
            // BUYER accounts don't need ledger accounts (they pay the platform, we payout to sellers)
            if (accountType != "SELLER") {
                logger.info("User $userId has account_type=$accountType. Only SELLER accounts get ledger accounts. Skipping.")
                acknowledgment.acknowledge()
                return
            }

            // Create SELLER_PAYABLE account for seller
            // accountId = userId (same as user ID)
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

            ledgerService.createAccount(
                accountId = userId,
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
