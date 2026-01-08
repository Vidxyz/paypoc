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
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Consumer for PayoutCompletedEvent from payment.events topic.
 * 
 * This consumer writes double-entry ledger entries when a payout is completed.
 * The entries reduce SELLER_PAYABLE (liability) and STRIPE_CLEARING (asset).
 * 
 * Flow:
 * 1. Receive generic message as Map<String, Any>
 * 2. Check the "type" field - only process "PAYOUT_COMPLETED" events
 * 3. Deserialize to PayoutCompletedEvent
 * 4. Get accounts:
 *    - STRIPE_CLEARING (credit - money transferred out)
 *    - SELLER_PAYABLE:{sellerId} (debit - reduce liability)
 * 5. Create double-entry transaction:
 *    DR SELLER_PAYABLE         amountCents (reduce money owed to seller)
 *    CR STRIPE_CLEARING         amountCents (money transferred out)
 * 6. Transaction is atomic, balanced, and idempotent
 */
@Component
class PayoutCompletedEventConsumer(
    private val ledgerService: LedgerService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Handles PAYOUT_COMPLETED events.
     * Called by PaymentCapturedEventConsumer (which acts as the event router).
     */
    @Transactional
    fun handlePayoutCompletedEvent(
        messageBytes: ByteArray,
        acknowledgment: Acknowledgment
    ) {
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
        
        // Check the event type - only process PAYOUT_COMPLETED events
        val eventType = message["type"] as? String
        if (eventType != "PAYOUT_COMPLETED") {
            logger.debug("Skipping message with type: $eventType (only processing PAYOUT_COMPLETED events)")
            acknowledgment.acknowledge() // Acknowledge to skip this message
            return
        }
        
        // Deserialize to PayoutCompletedEvent
        val event = try {
            objectMapper.convertValue(message, PayoutCompletedEvent::class.java)
        } catch (e: Exception) {
            logger.error("Failed to deserialize PAYOUT_COMPLETED event: ${e.message}", e)
            // Don't acknowledge - message will be retried
            throw e
        }
        
        try {
            logger.info("Received PayoutCompletedEvent for payout ${event.payoutId} (idempotency: ${event.idempotencyKey})")
            
            // Validate event
            require(event.amountCents > 0) { "Payout amount must be positive" }
            
            // Get accounts
            val stripeClearingAccount = getOrCreateAccount(
                accountType = AccountType.STRIPE_CLEARING,
                referenceId = null,
                currency = event.currency
            )
            
            val sellerPayableAccount = getOrCreateAccount(
                accountType = AccountType.SELLER_PAYABLE,
                referenceId = event.sellerId,
                currency = event.currency
            )
            
            // Create double-entry transaction
            // DR SELLER_PAYABLE (reduce money owed to seller)
            // CR STRIPE_CLEARING (money transferred out)
            val transactionRequest = CreateDoubleEntryTransactionRequest(
                referenceId = event.stripeTransferId,  // External reference (Stripe Transfer ID)
                idempotencyKey = event.idempotencyKey,
                description = "Payout: ${event.payoutId} - Seller: ${event.sellerId}",
                entries = listOf(
                    EntryRequest(
                        accountId = sellerPayableAccount.id,
                        direction = EntryDirection.DEBIT,
                        amountCents = event.amountCents,
                        currency = event.currency
                    ),
                    EntryRequest(
                        accountId = stripeClearingAccount.id,
                        direction = EntryDirection.CREDIT,
                        amountCents = event.amountCents,
                        currency = event.currency
                    )
                )
            )
            
            // Create transaction (atomic, balanced, idempotent)
            val transaction = ledgerService.createDoubleEntryTransaction(transactionRequest)
            
            logger.info(
                "Created ledger transaction ${transaction.id} for payout ${event.payoutId}. " +
                "DR SELLER_PAYABLE: ${event.amountCents}, " +
                "CR STRIPE_CLEARING: ${event.amountCents}"
            )
            
            // Commit offset only after successful processing
            acknowledgment.acknowledge()
        } catch (e: Exception) {
            logger.error("Error processing PayoutCompletedEvent for payout ${event.payoutId}", e)
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
 * Data class for PayoutCompletedEvent.
 * Matches the structure of PayoutCompletedEvent from payments service.
 * 
 * Note: The JSON includes a "type" field from PaymentMessage, which we ignore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PayoutCompletedEvent(
    val payoutId: UUID,
    val sellerId: String,
    val amountCents: Long,
    val currency: String,
    val stripeTransferId: String,
    val idempotencyKey: String
)

