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
 * Consumer for ChargebackWarningClosedEvent from payment.events topic.
 * 
 * This consumer writes double-entry ledger entries when a chargeback (dispute) is closed with warning_closed status.
 * BOTH the chargeback amount AND dispute fee are returned to STRIPE_CLEARING (unlike WON where only amount is returned).
 * 
 * Flow:
 * 1. Receive generic message as Map<String, Any>
 * 2. Check the "type" field - only process "CHARGEBACK_WARNING_CLOSED" events
 * 3. Deserialize to ChargebackWarningClosedEvent
 * 4. Get accounts:
 *    - CHARGEBACK_CLEARING (asset account - reduce both chargeback amount and fee)
 *    - STRIPE_CLEARING (asset account - money is returned)
 * 5. Create double-entry transaction:
 *    DR STRIPE_CLEARING         chargebackAmountCents + disputeFeeCents (both returned)
 *    CR CHARGEBACK_CLEARING     chargebackAmountCents + disputeFeeCents (clear both from clearing)
 * 6. Transaction is atomic, balanced, and idempotent
 */
@Component
class ChargebackWarningClosedEventConsumer(
    private val ledgerService: LedgerService,
    private val objectMapper: ObjectMapper,
    private val ledgerKafkaProducer: LedgerKafkaProducer
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Handles CHARGEBACK_WARNING_CLOSED events.
     * Called by PaymentEventConsumer (which acts as the event router).
     */
    @Transactional
    fun handleChargebackWarningClosedEvent(
        messageBytes: ByteArray,
        acknowledgment: Acknowledgment
    ) {
        logger.info("=== ChargebackWarningClosedEventConsumer.handleChargebackWarningClosedEvent CALLED ===")
        logger.info("Received CHARGEBACK_WARNING_CLOSED message from event router (${messageBytes.size} bytes)")
        
        // Manually deserialize bytes to Map<String, Any> to bypass Spring Kafka's type resolution
        val message = try {
            val typeRef = object : TypeReference<Map<String, Any>>() {}
            objectMapper.readValue(messageBytes, typeRef)
        } catch (e: Exception) {
            logger.error("Failed to deserialize message bytes to Map: ${e.message}", e)
            acknowledgment.acknowledge()
            return
        }
        
        // Check the event type - only process CHARGEBACK_WARNING_CLOSED events
        val eventType = message["type"] as? String
        if (eventType != "CHARGEBACK_WARNING_CLOSED") {
            logger.debug("Skipping message with type: $eventType (only processing CHARGEBACK_WARNING_CLOSED events)")
            acknowledgment.acknowledge()
            return
        }
        
        // Deserialize to ChargebackWarningClosedEvent
        val event = try {
            objectMapper.convertValue(message, ChargebackWarningClosedEvent::class.java)
        } catch (e: Exception) {
            logger.error("Failed to deserialize CHARGEBACK_WARNING_CLOSED event: ${e.message}", e)
            throw e
        }
        
        try {
            logger.info("Received ChargebackWarningClosedEvent for chargeback ${event.chargebackId} (idempotency: ${event.idempotencyKey})")
            
            // Validate event
            require(event.chargebackAmountCents > 0) { "Chargeback amount must be positive" }
            require(event.disputeFeeCents >= 0) { "Dispute fee cannot be negative" }
            
            val totalReturnAmount = event.chargebackAmountCents + event.disputeFeeCents
            
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
            // BOTH chargeback amount AND dispute fee are returned to STRIPE_CLEARING
            // This is different from WON where only the amount is returned
            val transactionRequest = CreateDoubleEntryTransactionRequest(
                referenceId = "${event.stripeDisputeId}_WARNING_CLOSED", // Unique reference for warning_closed outcome
                idempotencyKey = "${event.idempotencyKey}_WARNING_CLOSED",
                description = "Chargeback warning_closed: ${event.chargebackId} for payment ${event.paymentId} - Buyer: ${event.buyerId}, Seller: ${event.sellerId} (both amount and fee returned)",
                entries = listOf(
                    EntryRequest(
                        accountId = stripeClearingAccount.id,
                        direction = EntryDirection.DEBIT, // Money returned to Stripe Clearing
                        amountCents = totalReturnAmount,
                        currency = event.currency
                    ),
                    EntryRequest(
                        accountId = chargebackClearingAccount.id,
                        direction = EntryDirection.CREDIT, // Clear both amount and fee from clearing account
                        amountCents = totalReturnAmount,
                        currency = event.currency
                    )
                )
            )
            
            // Create transaction (atomic, balanced, idempotent)
            val transaction = ledgerService.createDoubleEntryTransaction(transactionRequest)
            
            logger.info(
                "Created ledger transaction ${transaction.id} for WARNING_CLOSED chargeback ${event.chargebackId}. " +
                "DR STRIPE_CLEARING: $totalReturnAmount (chargeback: ${event.chargebackAmountCents}, fee: ${event.disputeFeeCents} - both returned), " +
                "CR CHARGEBACK_CLEARING: $totalReturnAmount"
            )
            
            // Publish event to notify payments service
            val ledgerTransactionCreatedEvent = LedgerTransactionCreatedEvent(
                paymentId = event.paymentId,  // Include paymentId for reference
                refundId = null,  // This is a chargeback, not a refund
                chargebackId = event.chargebackId,
                ledgerTransactionId = transaction.id,
                idempotencyKey = "${event.idempotencyKey}_WARNING_CLOSED"
            )
            ledgerKafkaProducer.publishLedgerTransactionCreated(ledgerTransactionCreatedEvent)
            
            // Commit offset only after successful processing
            acknowledgment.acknowledge()
        } catch (e: Exception) {
            logger.error("Error processing ChargebackWarningClosedEvent for chargeback ${event.chargebackId}", e)
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
        val existing = ledgerService.findAccountByTypeAndReference(accountType, referenceId, currency)
        if (existing != null) {
            return existing
        }
        val accountId = UUID.nameUUIDFromBytes("${accountType.name}_${referenceId ?: ""}_$currency".toByteArray())
        return try {
            ledgerService.createAccount(accountId, accountType, referenceId, currency)
        } catch (e: IllegalArgumentException) {
            ledgerService.findAccountByTypeAndReference(accountType, referenceId, currency)
                ?: throw IllegalStateException("Failed to create or find account: $accountType (reference=$referenceId, currency=$currency)", e)
        }
    }
}

/**
 * Data class for ChargebackWarningClosedEvent.
 * Matches the structure of ChargebackWarningClosedEvent from payments service.
 * 
 * Note: The JSON includes a "type" field from PaymentMessage, which we ignore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChargebackWarningClosedEvent(
    val chargebackId: UUID,
    val paymentId: UUID,
    val chargebackAmountCents: Long,
    val disputeFeeCents: Long,
    val currency: String,
    val stripeDisputeId: String,
    val stripePaymentIntentId: String,
    val idempotencyKey: String,
    val buyerId: String,
    val sellerId: String
)

