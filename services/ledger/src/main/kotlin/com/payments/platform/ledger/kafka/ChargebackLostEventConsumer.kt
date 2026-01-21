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
 * Consumer for ChargebackLostEvent from payment.events topic.
 * 
 * This consumer writes double-entry ledger entries when a chargeback (dispute) is lost.
 * Money is permanently debited - reduce seller liability and platform revenue.
 * 
 * Flow:
 * 1. Receive generic message as Map<String, Any>
 * 2. Check the "type" field - only process "CHARGEBACK_LOST" events
 * 3. Deserialize to ChargebackLostEvent
 * 4. Get accounts:
 *    - CHARGEBACK_CLEARING (asset account - reduce chargeback amount)
 *    - SELLER_PAYABLE (liability account - reduce seller liability)
 *    - BUYIT_REVENUE (revenue account - reduce platform revenue)
 * 5. Create double-entry transaction:
 *    DR SELLER_PAYABLE          netSellerChargebackCents (reduce seller liability)
 *    DR BUYIT_REVENUE           platformFeeChargebackCents (reduce platform revenue)
 *    CR CHARGEBACK_CLEARING      chargebackAmountCents (reduce chargeback)
 *    Note: Dispute fee stays in CHARGEBACK_CLEARING as expense
 * 6. Transaction is atomic, balanced, and idempotent
 */
@Component
class ChargebackLostEventConsumer(
    private val ledgerService: LedgerService,
    private val objectMapper: ObjectMapper,
    private val ledgerKafkaProducer: LedgerKafkaProducer
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Handles CHARGEBACK_LOST events.
     * Called by PaymentEventConsumer (which acts as the event router).
     */
    @Transactional
    fun handleChargebackLostEvent(
        messageBytes: ByteArray,
        acknowledgment: Acknowledgment
    ) {
        logger.info("=== ChargebackLostEventConsumer.handleChargebackLostEvent CALLED ===")
        logger.info("Received CHARGEBACK_LOST message from event router (${messageBytes.size} bytes)")
        
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
        
        // Check the event type - only process CHARGEBACK_LOST events
        val eventType = message["type"] as? String
        if (eventType != "CHARGEBACK_LOST") {
            logger.debug("Skipping message with type: $eventType (only processing CHARGEBACK_LOST events)")
            acknowledgment.acknowledge() // Acknowledge to skip this message
            return
        }
        
        // Deserialize to ChargebackLostEvent
        val event = try {
            objectMapper.convertValue(message, ChargebackLostEvent::class.java)
        } catch (e: Exception) {
            logger.error("Failed to deserialize CHARGEBACK_LOST event: ${e.message}", e)
            // Don't acknowledge - message will be retried
            throw e
        }
        
        try {
            logger.info("Received ChargebackLostEvent for chargeback ${event.chargebackId} (idempotency: ${event.idempotencyKey})")
            
            // Validate event
            require(event.chargebackAmountCents > 0) { "Chargeback amount must be positive" }
            require(event.platformFeeCents >= 0) { "Platform fee cannot be negative" }
            require(event.netSellerAmountCents > 0) { "Net seller amount must be positive" }
            require(event.chargebackAmountCents == event.platformFeeCents + event.netSellerAmountCents) {
                "Chargeback calculation error: chargebackAmount != platformFee + netSellerAmount"
            }
            
            // Get accounts
            val chargebackClearingAccount = getOrCreateAccount(
                accountType = AccountType.CHARGEBACK_CLEARING,
                referenceId = null,
                currency = event.currency
            )
            
            val buyitRevenueAccount = getOrCreateAccount(
                accountType = AccountType.BUYIT_REVENUE,
                referenceId = null,
                currency = event.currency
            )
            
            // Create ledger entries for each seller
            val sellerEntries = event.sellerBreakdown.map { seller ->
                val sellerPayableAccount = getOrCreateAccount(
                    accountType = AccountType.SELLER_PAYABLE,
                    referenceId = seller.sellerId,
                    currency = event.currency
                )
                
                // Each seller's portion of the chargeback
                EntryRequest(
                    accountId = sellerPayableAccount.id,
                    direction = EntryDirection.DEBIT,
                    amountCents = seller.netSellerAmountCents,
                    currency = event.currency
                )
            }
            
            // Platform fee entries (one per seller)
            val platformFeeEntries = event.sellerBreakdown.map { seller ->
                EntryRequest(
                    accountId = buyitRevenueAccount.id,
                    direction = EntryDirection.DEBIT,
                    amountCents = seller.platformFeeCents,
                    currency = event.currency
                )
            }
            
            // Create double-entry transaction
            // Money is permanently debited - reduce seller liability and platform revenue for each seller
            // Dispute fee stays in CHARGEBACK_CLEARING as expense
            val transactionRequest = CreateDoubleEntryTransactionRequest(
                referenceId = event.stripeDisputeId,  // External reference (Stripe Dispute ID)
                transactionType = "CHARGEBACK_LOST",
                idempotencyKey = event.idempotencyKey,
                description = "Chargeback lost: ${event.chargebackId} for payment ${event.paymentId} - Buyer: ${event.buyerId}, ${event.sellerBreakdown.size} seller(s)",
                entries = sellerEntries + platformFeeEntries + listOf(
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
            
            val totalSellerDebit = event.sellerBreakdown.sumOf { it.netSellerAmountCents }
            val totalPlatformFeeDebit = event.sellerBreakdown.sumOf { it.platformFeeCents }
            logger.info(
                "Created ledger transaction ${transaction.id} for chargeback lost ${event.chargebackId}. " +
                "DR SELLER_PAYABLE (${event.sellerBreakdown.size} sellers): $totalSellerDebit (reduce seller liability), " +
                "DR BUYIT_REVENUE: $totalPlatformFeeDebit (reduce platform revenue), " +
                "CR CHARGEBACK_CLEARING: ${event.chargebackAmountCents} (dispute fee remains as expense)"
            )
            
            // Publish event to notify payments service
            val ledgerTransactionCreatedEvent = LedgerTransactionCreatedEvent(
                paymentId = event.paymentId,  // Include paymentId for reference
                refundId = null,  // This is a chargeback, not a refund
                chargebackId = event.chargebackId,
                ledgerTransactionId = transaction.id,
                idempotencyKey = event.idempotencyKey
            )
            ledgerKafkaProducer.publishLedgerTransactionCreated(ledgerTransactionCreatedEvent)
            
            // Commit offset only after successful processing
            acknowledgment.acknowledge()
        } catch (e: Exception) {
            logger.error("Error processing ChargebackLostEvent for chargeback ${event.chargebackId}", e)
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
 * Data class for ChargebackLostEvent.
 * Matches the structure of ChargebackLostEvent from payments service.
 * 
 * Note: The JSON includes a "type" field from PaymentMessage, which we ignore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChargebackLostEvent(
    val chargebackId: UUID,
    val paymentId: UUID,
    val chargebackAmountCents: Long,
    val platformFeeCents: Long,
    val netSellerAmountCents: Long,
    val currency: String,
    val stripeDisputeId: String,
    val stripePaymentIntentId: String,
    val idempotencyKey: String,
    val buyerId: String,
    val sellerBreakdown: List<SellerChargebackBreakdown>
)


