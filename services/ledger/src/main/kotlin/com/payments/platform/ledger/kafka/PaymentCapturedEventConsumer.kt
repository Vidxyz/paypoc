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
 * Consumer for PaymentCapturedEvent from payment.events topic.
 * 
 * This consumer writes double-entry ledger entries when a payment is captured.
 * 
 * Flow:
 * 1. Receive generic message as Map<String, Any>
 * 2. Check the "type" field - only process "PAYMENT_CAPTURED" events
 * 3. Deserialize to PaymentCapturedEvent
 * 4. Get or create accounts:
 *    - STRIPE_CLEARING (debit - money received from Stripe)
 *    - SELLER_PAYABLE:{sellerId} (credit - money owed to seller)
 *    - BUYIT_REVENUE (credit - platform commission)
 * 5. Create double-entry transaction:
 *    DR STRIPE_CLEARING         grossAmountCents
 *    CR SELLER_PAYABLE          netSellerAmountCents
 *    CR BUYIT_REVENUE           platformFeeCents
 * 6. Transaction is atomic, balanced, and idempotent
 */
@Component
class PaymentCapturedEventConsumer(
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
    fun handlePaymentCapturedEvent(
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
        
        // Check the event type - only process PAYMENT_CAPTURED events
        val eventType = message["type"] as? String
        if (eventType != "PAYMENT_CAPTURED") {
            logger.debug("Skipping message with type: $eventType (only processing PAYMENT_CAPTURED events)")
            acknowledgment.acknowledge() // Acknowledge to skip this message
            return
        }
        
        // Deserialize to PaymentCapturedEvent
        val event = try {
            objectMapper.convertValue(message, PaymentCapturedEvent::class.java)
        } catch (e: Exception) {
            logger.error("Failed to deserialize PAYMENT_CAPTURED event: ${e.message}", e)
            // Don't acknowledge - message will be retried
            throw e
        }
        try {
            logger.info("Received PaymentCapturedEvent for payment ${event.paymentId} (idempotency: ${event.idempotencyKey})")
            
            // Validate event
            require(event.grossAmountCents > 0) { "Gross amount must be positive" }
            require(event.platformFeeCents >= 0) { "Platform fee cannot be negative" }
            require(event.netSellerAmountCents > 0) { "Net seller amount must be positive" }
            require(event.grossAmountCents == event.platformFeeCents + event.netSellerAmountCents) {
                "Fee calculation error: grossAmount != platformFee + netSellerAmount"
            }
            
            // Get or create accounts
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
            
            // Create double-entry transaction
            // DR STRIPE_CLEARING (money received from Stripe)
            // CR SELLER_PAYABLE (money owed to seller)
            // CR BUYIT_REVENUE (platform commission)
            val transactionRequest = CreateDoubleEntryTransactionRequest(
                referenceId = event.stripePaymentIntentId,  // External reference (Stripe PaymentIntent ID)
                idempotencyKey = event.idempotencyKey,
                description = "Payment capture: ${event.paymentId} - Buyer: ${event.buyerId}, Seller: ${event.sellerId}",
                entries = listOf(
                    EntryRequest(
                        accountId = stripeClearingAccount.id,
                        direction = EntryDirection.DEBIT,
                        amountCents = event.grossAmountCents,
                        currency = event.currency
                    ),
                    EntryRequest(
                        accountId = sellerPayableAccount.id,
                        direction = EntryDirection.CREDIT,
                        amountCents = event.netSellerAmountCents,
                        currency = event.currency
                    ),
                    EntryRequest(
                        accountId = buyitRevenueAccount.id,
                        direction = EntryDirection.CREDIT,
                        amountCents = event.platformFeeCents,
                        currency = event.currency
                    )
                )
            )
            
            // Create transaction (atomic, balanced, idempotent)
            val transaction = ledgerService.createDoubleEntryTransaction(transactionRequest)
            
            logger.info(
                "Created ledger transaction ${transaction.id} for payment ${event.paymentId}. " +
                "DR STRIPE_CLEARING: ${event.grossAmountCents}, " +
                "CR SELLER_PAYABLE: ${event.netSellerAmountCents}, " +
                "CR BUYIT_REVENUE: ${event.platformFeeCents}"
            )
            
            // Commit offset only after successful processing
            acknowledgment.acknowledge()
        } catch (e: Exception) {
            logger.error("Error processing PaymentCapturedEvent for payment ${event.paymentId}", e)
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

