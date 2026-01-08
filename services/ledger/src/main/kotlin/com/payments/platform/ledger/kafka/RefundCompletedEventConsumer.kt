package com.payments.platform.ledger.kafka

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
 * Consumer for RefundCompletedEvent from payment.events topic.
 * 
 * This consumer writes double-entry ledger entries when a refund is completed.
 * The entries reverse the original payment transaction.
 * 
 * Flow:
 * 1. Receive generic message as Map<String, Any>
 * 2. Check the "type" field - only process "REFUND_COMPLETED" events
 * 3. Deserialize to RefundCompletedEvent
 * 4. Get accounts (same accounts as original payment):
 *    - STRIPE_CLEARING
 *    - SELLER_PAYABLE:{sellerId}
 *    - BUYIT_REVENUE
 * 5. Create reversed double-entry transaction:
 *    DR SELLER_PAYABLE          netSellerRefundCents (reverse original credit)
 *    DR BUYIT_REVENUE           platformFeeRefundCents (reverse original credit)
 *    CR STRIPE_CLEARING         refundAmountCents (reverse original debit)
 * 6. Transaction is atomic, balanced, and idempotent
 */
@Component
class RefundCompletedEventConsumer(
    private val ledgerService: LedgerService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${kafka.topics.events:payment.events}"],
        groupId = "ledger-service",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    fun handleRefundCompletedEvent(
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
        
        // Check the event type - only process REFUND_COMPLETED events
        val eventType = message["type"] as? String
        if (eventType != "REFUND_COMPLETED") {
            logger.debug("Skipping message with type: $eventType (only processing REFUND_COMPLETED events)")
            acknowledgment.acknowledge() // Acknowledge to skip this message
            return
        }
        
        // Deserialize to RefundCompletedEvent
        val event = try {
            objectMapper.convertValue(message, RefundCompletedEvent::class.java)
        } catch (e: Exception) {
            logger.error("Failed to deserialize REFUND_COMPLETED event: ${e.message}", e)
            // Don't acknowledge - message will be retried
            throw e
        }
        
        try {
            logger.info("Received RefundCompletedEvent for refund ${event.refundId} (idempotency: ${event.idempotencyKey})")
            
            // Validate event
            require(event.refundAmountCents > 0) { "Refund amount must be positive" }
            require(event.platformFeeRefundCents >= 0) { "Platform fee refund cannot be negative" }
            require(event.netSellerRefundCents > 0) { "Net seller refund must be positive" }
            require(event.refundAmountCents == event.platformFeeRefundCents + event.netSellerRefundCents) {
                "Refund calculation error: refundAmount != platformFeeRefund + netSellerRefund"
            }
            
            // Get accounts (same accounts as original payment)
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
            
            val buyitRevenueAccount = getOrCreateAccount(
                accountType = AccountType.BUYIT_REVENUE,
                referenceId = null,
                currency = event.currency
            )
            
            // Create reversed double-entry transaction
            // Original transaction was:
            //   DR STRIPE_CLEARING (money received from Stripe)
            //   CR SELLER_PAYABLE (money owed to seller)
            //   CR BUYIT_REVENUE (platform commission)
            // 
            // Refund reverses this:
            //   DR SELLER_PAYABLE (reduce money owed to seller)
            //   DR BUYIT_REVENUE (reduce platform commission)
            //   CR STRIPE_CLEARING (money returned to Stripe)
            val transactionRequest = CreateDoubleEntryTransactionRequest(
                referenceId = event.stripeRefundId,  // External reference (Stripe Refund ID)
                idempotencyKey = event.idempotencyKey,
                description = "Refund: ${event.refundId} for payment ${event.paymentId} - Buyer: ${event.buyerId}, Seller: ${event.sellerId}",
                entries = listOf(
                    EntryRequest(
                        accountId = sellerPayableAccount.id,
                        direction = EntryDirection.DEBIT,
                        amountCents = event.netSellerRefundCents,
                        currency = event.currency
                    ),
                    EntryRequest(
                        accountId = buyitRevenueAccount.id,
                        direction = EntryDirection.DEBIT,
                        amountCents = event.platformFeeRefundCents,
                        currency = event.currency
                    ),
                    EntryRequest(
                        accountId = stripeClearingAccount.id,
                        direction = EntryDirection.CREDIT,
                        amountCents = event.refundAmountCents,
                        currency = event.currency
                    )
                )
            )
            
            // Create transaction (atomic, balanced, idempotent)
            val transaction = ledgerService.createDoubleEntryTransaction(transactionRequest)
            
            logger.info(
                "Created ledger transaction ${transaction.id} for refund ${event.refundId}. " +
                "DR SELLER_PAYABLE: ${event.netSellerRefundCents}, " +
                "DR BUYIT_REVENUE: ${event.platformFeeRefundCents}, " +
                "CR STRIPE_CLEARING: ${event.refundAmountCents}"
            )
            
            // Commit offset only after successful processing
            acknowledgment.acknowledge()
        } catch (e: Exception) {
            logger.error("Error processing RefundCompletedEvent for refund ${event.refundId}", e)
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
 * Data class for RefundCompletedEvent.
 * Matches the structure of RefundCompletedEvent from payments service.
 */
data class RefundCompletedEvent(
    val refundId: UUID,
    val paymentId: UUID,
    val refundAmountCents: Long,
    val platformFeeRefundCents: Long,
    val netSellerRefundCents: Long,
    val currency: String,
    val stripeRefundId: String,
    val stripePaymentIntentId: String,
    val idempotencyKey: String,
    val buyerId: String,
    val sellerId: String
)

