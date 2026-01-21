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
 * Consumer for ChargebackCreatedEvent from payment.events topic.
 * 
 * This consumer writes double-entry ledger entries when a chargeback (dispute) is created.
 * Money is immediately debited from STRIPE_CLEARING when dispute is created.
 * 
 * Flow:
 * 1. Receive generic message as Map<String, Any>
 * 2. Check the "type" field - only process "CHARGEBACK_CREATED" events
 * 3. Deserialize to ChargebackCreatedEvent
 * 4. Get accounts:
 *    - CHARGEBACK_CLEARING (asset account for chargeback amounts)
 *    - STRIPE_CLEARING (asset account - money is debited)
 * 5. Create double-entry transaction:
 *    DR CHARGEBACK_CLEARING    chargebackAmountCents + disputeFeeCents (money held in chargeback)
 *    CR STRIPE_CLEARING         chargebackAmountCents + disputeFeeCents (money debited from Stripe)
 * 6. Transaction is atomic, balanced, and idempotent
 */
@Component
class ChargebackCreatedEventConsumer(
    private val ledgerService: LedgerService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Handles CHARGEBACK_CREATED events.
     * Called by PaymentEventConsumer (which acts as the event router).
     */
    @Transactional
    fun handleChargebackCreatedEvent(
        messageBytes: ByteArray,
        acknowledgment: Acknowledgment
    ) {
        logger.info("=== ChargebackCreatedEventConsumer.handleChargebackCreatedEvent CALLED ===")
        logger.info("Received CHARGEBACK_CREATED message from event router (${messageBytes.size} bytes)")
        
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
        
        // Check the event type - only process CHARGEBACK_CREATED events
        val eventType = message["type"] as? String
        if (eventType != "CHARGEBACK_CREATED") {
            logger.debug("Skipping message with type: $eventType (only processing CHARGEBACK_CREATED events)")
            acknowledgment.acknowledge() // Acknowledge to skip this message
            return
        }
        
        // Deserialize to ChargebackCreatedEvent
        val event = try {
            objectMapper.convertValue(message, ChargebackCreatedEvent::class.java)
        } catch (e: Exception) {
            logger.error("Failed to deserialize CHARGEBACK_CREATED event: ${e.message}", e)
            // Don't acknowledge - message will be retried
            throw e
        }
        
        try {
            logger.info("Received ChargebackCreatedEvent for chargeback ${event.chargebackId} (idempotency: ${event.idempotencyKey})")
            
            // Validate event
            require(event.chargebackAmountCents > 0) { "Chargeback amount must be positive" }
            require(event.disputeFeeCents >= 0) { "Dispute fee cannot be negative" }
            
            val totalDebitCents = event.chargebackAmountCents + event.disputeFeeCents
            
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
            // Money is immediately debited from STRIPE_CLEARING when dispute is created
            // We hold it in CHARGEBACK_CLEARING until dispute is resolved
            val transactionRequest = CreateDoubleEntryTransactionRequest(
                referenceId = event.stripeDisputeId,  // External reference (Stripe Dispute ID)
                transactionType = "CHARGEBACK_CREATED",
                idempotencyKey = event.idempotencyKey,
                description = "Chargeback created: ${event.chargebackId} for payment ${event.paymentId} - Buyer: ${event.buyerId}, ${event.sellerBreakdown.size} seller(s), Reason: ${event.reason ?: "unknown"}",
                entries = listOf(
                    EntryRequest(
                        accountId = chargebackClearingAccount.id,
                        direction = EntryDirection.DEBIT,
                        amountCents = totalDebitCents,
                        currency = event.currency
                    ),
                    EntryRequest(
                        accountId = stripeClearingAccount.id,
                        direction = EntryDirection.CREDIT,
                        amountCents = totalDebitCents,
                        currency = event.currency
                    )
                )
            )
            
            // Create transaction (atomic, balanced, idempotent)
            val transaction = ledgerService.createDoubleEntryTransaction(transactionRequest)
            
            logger.info(
                "Created ledger transaction ${transaction.id} for chargeback ${event.chargebackId}. " +
                "DR CHARGEBACK_CLEARING: $totalDebitCents (chargeback: ${event.chargebackAmountCents}, fee: ${event.disputeFeeCents}), " +
                "CR STRIPE_CLEARING: $totalDebitCents"
            )
            
            // Commit offset only after successful processing
            acknowledgment.acknowledge()
        } catch (e: Exception) {
            logger.error("Error processing ChargebackCreatedEvent for chargeback ${event.chargebackId}", e)
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
 * Data class for ChargebackCreatedEvent.
 * Matches the structure of ChargebackCreatedEvent from payments service.
 * 
 * Note: The JSON includes a "type" field from PaymentMessage, which we ignore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChargebackCreatedEvent(
    val chargebackId: UUID,
    val paymentId: UUID,
    val chargebackAmountCents: Long,
    val disputeFeeCents: Long,
    val currency: String,
    val stripeDisputeId: String,
    val stripeChargeId: String,
    val stripePaymentIntentId: String,
    val reason: String?,
    val idempotencyKey: String,
    val buyerId: String,
    val sellerBreakdown: List<SellerChargebackBreakdown>
)


