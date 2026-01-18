package com.payments.platform.ledger.kafka

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
 * Handler for PaymentCapturedEvent.
 * 
 * This handler writes double-entry ledger entries when a payment is captured.
 * Called by PaymentEventConsumer (which acts as the event router).
 * 
 * Flow:
 * 1. Receive deserialized message as Map<String, Any>
 * 2. Deserialize to PaymentCapturedEvent
 * 3. Get or create accounts:
 *    - STRIPE_CLEARING (debit - money received from Stripe into platform account)
 *    - SELLER_PAYABLE:{sellerId} (credit - money owed to seller, not yet transferred)
 *    - BUYIT_REVENUE (credit - platform commission)
 * 4. Create double-entry transaction:
 *    DR STRIPE_CLEARING         grossAmountCents (money in platform Stripe account)
 *    CR SELLER_PAYABLE          netSellerAmountCents (liability - money owed to seller)
 *    CR BUYIT_REVENUE           platformFeeCents (platform commission)
 * 5. Transaction is atomic, balanced, and idempotent
 * 
 * Note: Money is now held in platform account. Payouts are handled separately via PayoutCompletedEvent.
 */
@Component
class PaymentCapturedEventConsumer(
    private val ledgerService: LedgerService,
    private val objectMapper: ObjectMapper,
    private val ledgerKafkaProducer: LedgerKafkaProducer
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    /**
     * Handles PAYMENT_CAPTURED events.
     * Called by PaymentEventConsumer (which acts as the event router).
     */
    @Transactional
    fun handlePaymentCapturedEvent(
        message: Map<String, Any>,
        acknowledgment: Acknowledgment
    ) {
        logger.info("=== PaymentCapturedEventConsumer.handlePaymentCapturedEvent CALLED ===")
        
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
            require(event.sellerBreakdown.isNotEmpty()) { "Seller breakdown cannot be empty" }
            
            // Validate seller breakdown sums match totals
            val totalSellerGross = event.sellerBreakdown.sumOf { it.sellerGrossAmountCents }
            val totalSellerPlatformFee = event.sellerBreakdown.sumOf { it.platformFeeCents }
            val totalSellerNet = event.sellerBreakdown.sumOf { it.netSellerAmountCents }
            require(totalSellerGross == event.grossAmountCents) {
                "Seller breakdown gross sum ($totalSellerGross) must equal total gross (${event.grossAmountCents})"
            }
            require(totalSellerPlatformFee == event.platformFeeCents) {
                "Seller breakdown platform fee sum ($totalSellerPlatformFee) must equal total platform fee (${event.platformFeeCents})"
            }
            require(totalSellerNet == event.netSellerAmountCents) {
                "Seller breakdown net sum ($totalSellerNet) must equal total net (${event.netSellerAmountCents})"
            }
            
            // Get or create accounts
            val stripeClearingAccount = getOrCreateAccount(
                accountType = AccountType.STRIPE_CLEARING,
                referenceId = null,
                currency = event.currency
            )
            
            val buyitRevenueAccount = getOrCreateAccount(
                accountType = AccountType.BUYIT_REVENUE,
                referenceId = null,
                currency = event.currency
            )
            
            // Get or create SELLER_PAYABLE account for each seller
            val sellerPayableAccounts = event.sellerBreakdown.map { seller ->
                seller.sellerId to getOrCreateAccount(
                    accountType = AccountType.SELLER_PAYABLE,
                    referenceId = seller.sellerId,
                    currency = event.currency
                )
            }.toMap()
            
            // Create double-entry transaction
            // DR STRIPE_CLEARING (money received from Stripe - total gross)
            // CR SELLER_PAYABLE per seller (money owed to each seller - their net amount)
            // CR BUYIT_REVENUE (platform commission - total platform fee)
            val entries = mutableListOf<EntryRequest>().apply {
                // DEBIT: STRIPE_CLEARING (total gross)
                add(
                    EntryRequest(
                        accountId = stripeClearingAccount.id,
                        direction = EntryDirection.DEBIT,
                        amountCents = event.grossAmountCents,
                        currency = event.currency
                    )
                )
                
                // CREDIT: SELLER_PAYABLE for each seller
                event.sellerBreakdown.forEach { seller ->
                    val sellerAccount = sellerPayableAccounts[seller.sellerId]
                        ?: throw IllegalStateException("Failed to get/create account for seller ${seller.sellerId}")
                    add(
                        EntryRequest(
                            accountId = sellerAccount.id,
                            direction = EntryDirection.CREDIT,
                            amountCents = seller.netSellerAmountCents,
                            currency = event.currency
                        )
                    )
                }
                
                // CREDIT: BUYIT_REVENUE (total platform fee)
                add(
                    EntryRequest(
                        accountId = buyitRevenueAccount.id,
                        direction = EntryDirection.CREDIT,
                        amountCents = event.platformFeeCents,
                        currency = event.currency
                    )
                )
            }
            
            val sellerIds = event.sellerBreakdown.joinToString(", ") { it.sellerId }
            val transactionRequest = CreateDoubleEntryTransactionRequest(
                referenceId = event.stripePaymentIntentId,  // External reference (Stripe PaymentIntent ID)
                idempotencyKey = event.idempotencyKey,
                description = "Payment capture: ${event.paymentId} (order: ${event.orderId}) - Buyer: ${event.buyerId}, Sellers: $sellerIds",
                entries = entries
            )
            
            // Create transaction (atomic, balanced, idempotent)
            val transaction = ledgerService.createDoubleEntryTransaction(transactionRequest)
            
            val sellerCredits = event.sellerBreakdown.joinToString(", ") { 
                "${it.sellerId}: ${it.netSellerAmountCents}" 
            }
            logger.info(
                "Created ledger transaction ${transaction.id} for payment ${event.paymentId} (order: ${event.orderId}). " +
                "DR STRIPE_CLEARING: ${event.grossAmountCents}, " +
                "CR SELLER_PAYABLE: [$sellerCredits], " +
                "CR BUYIT_REVENUE: ${event.platformFeeCents}"
            )
            
            // Publish event to notify payments service
            val ledgerTransactionCreatedEvent = LedgerTransactionCreatedEvent(
                paymentId = event.paymentId,
                refundId = null,  // This is a payment, not a refund
                ledgerTransactionId = transaction.id,
                idempotencyKey = event.idempotencyKey
            )
            ledgerKafkaProducer.publishLedgerTransactionCreated(ledgerTransactionCreatedEvent)
            
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

