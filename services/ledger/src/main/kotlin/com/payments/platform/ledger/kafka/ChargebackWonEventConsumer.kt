package com.payments.platform.ledger.kafka

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.payments.platform.ledger.domain.AccountType
import com.payments.platform.ledger.domain.CreateDoubleEntryTransactionRequest
import com.payments.platform.ledger.domain.EntryDirection
import com.payments.platform.ledger.domain.EntryRequest
import com.payments.platform.ledger.service.LedgerService
import org.slf4j.LoggerFactory
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Consumer for ChargebackWonEvent from payment.events topic.
 * 
 * This consumer writes double-entry ledger entries when a chargeback (dispute) is won.
 * Money is returned to STRIPE_CLEARING when platform wins the dispute.
 * 
 * Flow:
 * 1. Receive generic message as Map<String, Any>
 * 2. Check the "type" field - only process "CHARGEBACK_WON" events
 * 3. Deserialize to ChargebackWonEvent
 * 4. Get accounts:
 *    - CHARGEBACK_CLEARING (asset account - reduce chargeback amount)
 *    - STRIPE_CLEARING (asset account - money is returned)
 * 5. Create double-entry transaction:
 *    DR STRIPE_CLEARING         chargebackAmountCents (money returned)
 *    CR CHARGEBACK_CLEARING     chargebackAmountCents (reduce chargeback)
 *    Note: Dispute fee is NOT refunded - it stays in CHARGEBACK_CLEARING as expense
 * 6. Transaction is atomic, balanced, and idempotent
 */
@Component
class ChargebackWonEventConsumer(
    private val ledgerService: LedgerService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Handles CHARGEBACK_WON events.
     * Called by PaymentEventConsumer (which acts as the event router).
     */
    @Transactional
    fun handleChargebackWonEvent(
        messageBytes: ByteArray,
        acknowledgment: Acknowledgment
    ) {
        logger.info("=== ChargebackWonEventConsumer.handleChargebackWonEvent CALLED ===")
        logger.info("Received CHARGEBACK_WON message from event router (${messageBytes.size} bytes)")
        
        // Manually deserialize bytes to Map<String, Any> to bypass Spring Kafka's type resolution
        val message = try {
            val typeRef = object : TypeReference<Map<String, Any>>() {}
            objectMapper.readValue(messageBytes, typeRef)
        } catch (e: Exception) {
            logger.error("Failed to deserialize message bytes to Map: ${e.message}", e)
            // Acknowledge to skip this malformed message
            acknowledgment.acknowledge()
            return
        }
        
        // Check the event type - only process CHARGEBACK_WON events
        val eventType = message["type"] as? String
        if (eventType != "CHARGEBACK_WON") {
            logger.debug("Skipping message with type: $eventType (only processing CHARGEBACK_WON events)")
            acknowledgment.acknowledge() // Acknowledge to skip this message
            return
        }
        
        // Deserialize to ChargebackWonEvent
        val event = try {
            objectMapper.convertValue(message, ChargebackWonEvent::class.java)
        } catch (e: Exception) {
            logger.error("Failed to deserialize CHARGEBACK_WON event: ${e.message}", e)
            // Don't acknowledge - message will be retried
            throw e
        }
        
        try {
            logger.info("Received ChargebackWonEvent for chargeback ${event.chargebackId} (idempotency: ${event.idempotencyKey})")
            
            // Validate event
            require(event.chargebackAmountCents > 0) { "Chargeback amount must be positive" }
            
            // Get accounts
            val chargebackClearingAccount = getOrCreateAccount(
                accountType = AccountType.CHARGEBACK_CLEARING,
                referenceId = null,
                currency = event.currency
            )
            
            val stripeClearingAccount = getOrCreateAccount(
                accountType = AccountType.STRIPE_CLEARING,
                referenceId = null,
                currency = event.currency
            )
            
            // Create double-entry transaction
            // Money is returned to STRIPE_CLEARING when platform wins
            // Only the chargeback amount is returned - dispute fee stays as expense
            val transactionRequest = CreateDoubleEntryTransactionRequest(
                referenceId = event.stripeDisputeId,  // External reference (Stripe Dispute ID)
                idempotencyKey = event.idempotencyKey,
                description = "Chargeback won: ${event.chargebackId} for payment ${event.paymentId} - Buyer: ${event.buyerId}, Seller: ${event.sellerId}",
                entries = listOf(
                    EntryRequest(
                        accountId = stripeClearingAccount.id,
                        direction = EntryDirection.DEBIT,
                        amountCents = event.chargebackAmountCents,
                        currency = event.currency
                    ),
                    EntryRequest(
                        accountId = chargebackClearingAccount.id,
                        direction = EntryDirection.CREDIT,
                        amountCents = event.chargebackAmountCents,
                        currency = event.currency
                    )
                )
            )
            
            // Create transaction (atomic, balanced, idempotent)
            val transaction = ledgerService.createDoubleEntryTransaction(transactionRequest)
            
            logger.info(
                "Created ledger transaction ${transaction.id} for chargeback won ${event.chargebackId}. " +
                "DR STRIPE_CLEARING: ${event.chargebackAmountCents} (money returned), " +
                "CR CHARGEBACK_CLEARING: ${event.chargebackAmountCents} (dispute fee remains as expense)"
            )
            
            // Commit offset only after successful processing
            acknowledgment.acknowledge()
        } catch (e: Exception) {
            logger.error("Error processing ChargebackWonEvent for chargeback ${event.chargebackId}", e)
            // Don't acknowledge - message will be retried by Kafka
            throw e
        }
    }
    
    /**
     * Gets or creates an account.
     * 
     * Uses idempotent account creation - if account already exists, returns it.
     */
    private fun getOrCreateAccount(
        accountType: AccountType,
        referenceId: String?,
        currency: String
    ): com.payments.platform.ledger.domain.Account {
        // Try to find existing account
        val existing = ledgerService.findAccountByTypeAndReference(accountType, referenceId, currency)
        if (existing != null) {
            return existing
        }
        
        // Create new account (idempotent - uses deterministic UUID based on account type + reference + currency)
        val accountId = UUID.nameUUIDFromBytes("${accountType.name}_${referenceId ?: ""}_$currency".toByteArray())
        
        return try {
            ledgerService.createAccount(accountId, accountType, referenceId, currency)
        } catch (e: IllegalArgumentException) {
            // Account might have been created concurrently - try to find it again
            ledgerService.findAccountByTypeAndReference(accountType, referenceId, currency)
                ?: throw IllegalStateException("Failed to create or find account: $accountType (reference=$referenceId, currency=$currency)", e)
        }
    }
}

/**
 * Data class for ChargebackWonEvent.
 * Matches the structure of ChargebackWonEvent from payments service.
 * 
 * Note: The JSON includes a "type" field from PaymentMessage, which we ignore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChargebackWonEvent(
    val chargebackId: UUID,
    val paymentId: UUID,
    val chargebackAmountCents: Long,
    val currency: String,
    val stripeDisputeId: String,
    val stripePaymentIntentId: String,
    val idempotencyKey: String,
    val buyerId: String,
    val sellerId: String
)

