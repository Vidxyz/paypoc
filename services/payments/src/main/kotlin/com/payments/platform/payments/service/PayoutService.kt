package com.payments.platform.payments.service

import com.payments.platform.payments.client.LedgerClient
import com.payments.platform.payments.client.LedgerClientException
import com.payments.platform.payments.domain.Payout
import com.payments.platform.payments.domain.PayoutState
import com.payments.platform.payments.domain.PayoutStateMachine
import com.payments.platform.payments.kafka.PayoutCompletedEvent
import com.payments.platform.payments.kafka.PaymentKafkaProducer
import com.payments.platform.payments.persistence.PayoutEntity
import com.payments.platform.payments.persistence.PayoutRepository
import com.payments.platform.payments.persistence.SellerStripeAccountRepository
import com.payments.platform.payments.stripe.StripeService
import com.payments.platform.payments.stripe.StripeServiceException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Payout orchestration service.
 * 
 * Key principle: Ledger writes ONLY after Stripe confirms transfer completion.
 * - Payout creation: NO ledger write (no money has moved yet)
 * - Stripe webhook confirms transfer: THEN write to ledger
 * - This ensures: "Ledger only records actual money movement"
 */
@Service
class PayoutService(
    private val payoutRepository: PayoutRepository,
    private val sellerStripeAccountRepository: SellerStripeAccountRepository,
    private val stripeService: StripeService,
    private val ledgerClient: LedgerClient,
    private val kafkaProducer: PaymentKafkaProducer,
    private val stateMachine: PayoutStateMachine
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    /**
     * Creates a payout for a seller.
     * 
     * Order of operations:
     * 1. Validate seller has Stripe account configured
     * 2. Generate payout IDs and idempotency key
     * 3. Check idempotency
     * 4. Create Stripe Transfer
     * 5. Persist payout (state = PROCESSING, ledger_transaction_id = NULL)
     * 6. Return payout
     * 
     * NO ledger write at this stage - money hasn't moved yet.
     * Ledger write happens AFTER Stripe webhook confirms transfer completion.
     * 
     * @param sellerId Seller ID
     * @param amountCents Amount to payout in cents
     * @param currency Currency code
     * @param description Optional description
     * @return Created payout
     * @throws PayoutCreationException if payout creation fails
     */
    @Transactional
    fun createPayout(
        sellerId: String,
        amountCents: Long,
        currency: String,
        description: String? = null
    ): Payout {
        // Validate amount
        require(amountCents > 0) { "Payout amount must be positive" }
        
        // Lookup seller's Stripe account
        val sellerStripeAccount = sellerStripeAccountRepository
            .findBySellerIdAndCurrency(sellerId, currency)
            ?: throw PayoutCreationException(
                "Seller $sellerId does not have a Stripe account configured for currency $currency. " +
                "Please configure the seller's Stripe account before processing payouts."
            )
        
        // Validate that Stripe account ID is set (cannot be null for payouts)
        val stripeAccountId = sellerStripeAccount.stripeAccountId
            ?: throw PayoutCreationException(
                "Seller $sellerId has a Stripe account entry for currency $currency, but Stripe account ID is not configured. " +
                "Please configure the seller's Stripe account ID before processing payouts."
            )
        
        // Generate payout ID and idempotency key
        val payoutId = UUID.randomUUID()
        val idempotencyKey = "payout_${payoutId}_${System.currentTimeMillis()}"
        
        // Check idempotency (if payout already exists, return it)
        val existingPayout = payoutRepository.findByIdempotencyKey(idempotencyKey)
        if (existingPayout != null) {
            logger.info("Payout with idempotency key $idempotencyKey already exists, returning existing payout")
            return existingPayout.toDomain()
        }
        
        // Create payout record FIRST (with null Stripe Transfer ID temporarily)
        // This ensures the payout exists in the database before the webhook arrives
        // We'll update it with the Stripe Transfer ID after creating the transfer
        val payout = Payout(
            id = payoutId,
            sellerId = sellerId,
            amountCents = amountCents,
            currency = currency,
            state = PayoutState.PROCESSING,
            stripeTransferId = null,  // Will be updated after Stripe Transfer is created
            ledgerTransactionId = null,  // NULL until transfer.created webhook triggers ledger write
            idempotencyKey = idempotencyKey,
            description = description,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            completedAt = null,
            failureReason = null
        )
        
        val entity = PayoutEntity.fromDomain(payout)
        val saved = payoutRepository.save(entity)
        
        // Flush to ensure the payout is persisted before creating Stripe Transfer
        // This prevents race condition where webhook arrives before payout is saved
        payoutRepository.flush()
        
        // Create Stripe Transfer AFTER payout is saved
        // The webhook will arrive, but the payout will already exist in the database
        val stripeTransfer = try {
            stripeService.createTransfer(
                amountCents = amountCents,
                currency = currency,
                destinationAccountId = stripeAccountId,
                metadata = mapOf(
                    "payoutId" to payoutId.toString(),
                    "sellerId" to sellerId,
                    "idempotencyKey" to idempotencyKey
                )
            )
        } catch (e: StripeServiceException) {
            // If Stripe Transfer creation fails, delete the payout we just created
            payoutRepository.delete(entity)
            throw PayoutCreationException("Failed to create Stripe Transfer: ${e.message}", e)
        }
        
        // Update payout with Stripe Transfer ID (stripeTransferId is a var, so we can update it directly)
        saved.stripeTransferId = stripeTransfer.id
        val finalSaved = payoutRepository.save(saved)
        
        logger.info(
            "Created payout ${finalSaved.id} for seller $sellerId " +
            "(Stripe Transfer ID: ${stripeTransfer.id}, amount: $amountCents ${currency})"
        )
        
        return finalSaved.toDomain()
    }
    
    /**
     * Creates a payout for all pending funds for a seller.
     * 
     * This queries the ledger for SELLER_PAYABLE balance and creates a payout for that amount.
     * 
     * @param sellerId Seller ID
     * @param currency Currency code
     * @return Created payout
     * @throws PayoutCreationException if payout creation fails
     */
    @Transactional
    fun createPayoutForPendingFunds(
        sellerId: String,
        currency: String
    ): Payout {
        // Get seller's payable account ID (deterministic UUID based on account type + reference + currency)
        val accountId = UUID.nameUUIDFromBytes("SELLER_PAYABLE_${sellerId}_$currency".toByteArray())
        
        // Query ledger for balance
        val balance = try {
            ledgerClient.getBalance(accountId)
        } catch (e: LedgerClientException) {
            if (e.message?.contains("not found") == true) {
                // Account doesn't exist yet - no pending funds
                throw PayoutCreationException(
                    "No pending funds found for seller $sellerId (currency: $currency). " +
                    "Account does not exist in ledger."
                )
            }
            throw PayoutCreationException("Failed to query ledger balance: ${e.message}", e)
        }
        
        // Validate balance is positive
        if (balance.balanceCents <= 0) {
            throw PayoutCreationException(
                "No pending funds to payout for seller $sellerId (currency: $currency). " +
                "Current balance: ${balance.balanceCents} cents"
            )
        }
        
        // Create payout for the full balance
        return createPayout(
            sellerId = sellerId,
            amountCents = balance.balanceCents,
            currency = currency,
            description = "Payout for pending funds (balance: ${balance.balanceCents} cents)"
        )
    }
    
    /**
     * Transitions a payout to a new state.
     * Enforces state machine rules.
     * 
     * @param payoutId Payout ID
     * @param newState New state
     * @param completedAt Optional completion timestamp (for COMPLETED state)
     * @param failureReason Optional failure reason (for FAILED state)
     */
    @Transactional
    fun transitionPayout(
        payoutId: UUID,
        newState: PayoutState,
        completedAt: Instant? = null,
        failureReason: String? = null
    ) {
        val payoutEntity = payoutRepository.findById(payoutId).orElseThrow {
            IllegalArgumentException("Payout not found: $payoutId")
        }
        
        val currentState = payoutEntity.state
        
        // Enforce state machine
        stateMachine.transition(currentState, newState)
        
        payoutEntity.state = newState
        payoutEntity.updatedAt = Instant.now()
        
        if (newState == PayoutState.COMPLETED && completedAt != null) {
            payoutEntity.completedAt = completedAt
        }
        
        if (newState == PayoutState.FAILED && failureReason != null) {
            payoutEntity.failureReason = failureReason
        }
        
        payoutRepository.save(payoutEntity)
        
        logger.info("Payout $payoutId transitioned from $currentState to $newState")
    }
    
    /**
     * Gets a payout by ID.
     * 
     * @param payoutId Payout ID
     * @return Payout
     */
    fun getPayout(payoutId: UUID): Payout {
        val payoutEntity = payoutRepository.findById(payoutId).orElseThrow {
            IllegalArgumentException("Payout not found: $payoutId")
        }
        return payoutEntity.toDomain()
    }
    
    /**
     * Gets all payouts for a seller.
     * 
     * @param sellerId Seller ID
     * @return List of payouts
     */
    fun getPayoutsBySellerId(sellerId: String): List<Payout> {
        return payoutRepository.findBySellerId(sellerId).map { it.toDomain() }
    }
    
    /**
     * Finds a payout by Stripe Transfer ID.
     * 
     * @param stripeTransferId Stripe Transfer ID
     * @return Payout if found, null otherwise
     */
    fun getPayoutByStripeTransferId(stripeTransferId: String): Payout? {
        return payoutRepository.findByStripeTransferId(stripeTransferId)?.toDomain()
    }
}

/**
 * Exception thrown when payout creation fails.
 */
class PayoutCreationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

