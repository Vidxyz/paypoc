package com.payments.platform.payments.service

import com.payments.platform.payments.api.ReconciliationReport
import com.payments.platform.payments.api.ReconciliationRequest
import com.payments.platform.payments.api.ReconciliationSummary
import com.payments.platform.payments.client.LedgerClient
import com.payments.platform.payments.client.dto.LedgerTransactionQueryResponse
import com.payments.platform.payments.domain.Discrepancy
import com.payments.platform.payments.domain.DiscrepancySeverity
import com.payments.platform.payments.domain.DiscrepancyType
import com.payments.platform.payments.domain.ReconciliationDiscrepancy
import com.payments.platform.payments.domain.ReconciliationRun
import com.payments.platform.payments.persistence.ReconciliationDiscrepancyEntity
import com.payments.platform.payments.persistence.ReconciliationDiscrepancyRepository
import com.payments.platform.payments.persistence.ReconciliationRunEntity
import com.payments.platform.payments.persistence.ReconciliationRunRepository
import com.payments.platform.payments.stripe.StripeService
import com.stripe.model.BalanceTransaction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Service for reconciling Stripe balance transactions with ledger transactions.
 * 
 * This service compares Stripe's financial records with our internal ledger to ensure
 * books are balanced and identify any discrepancies.
 * 
 * All reconciliation runs and discrepancies are persisted for audit trail purposes.
 */
@Service
class ReconciliationService(
    private val stripeService: StripeService,
    private val ledgerClient: LedgerClient,
    private val reconciliationRunRepository: ReconciliationRunRepository,
    private val reconciliationDiscrepancyRepository: ReconciliationDiscrepancyRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    /**
     * Runs reconciliation for the specified date range.
     * 
     * Persists the reconciliation run and all discrepancies for audit trail.
     * 
     * @param request Reconciliation request with date range and optional currency filter
     * @return Reconciliation report with summary and discrepancies
     */
    @Transactional
    fun runReconciliation(request: ReconciliationRequest): ReconciliationReport {
        val reconciliationId = UUID.randomUUID()
        val runAt = Instant.now()
        
        logger.info(
            "Starting reconciliation $reconciliationId " +
            "from ${request.startDate} to ${request.endDate} " +
            "${if (request.currency != null) "(currency: ${request.currency})" else ""}"
        )
        
        // Validate date range
        require(request.startDate.isBefore(request.endDate) || request.startDate == request.endDate) {
            "Start date must be before or equal to end date"
        }
        
        // Add small buffer before endDate to account for in-flight transactions
        val effectiveEndDate = request.endDate.minusSeconds(300) // 5 minutes buffer
        
        // Fetch Stripe balance transactions
        val stripeTransactions = fetchStripeBalanceTransactions(
            request.startDate,
            effectiveEndDate,
            request.currency
        )
        
        // Fetch ledger transactions
        val ledgerTransactions = fetchLedgerTransactions(
            request.startDate,
            effectiveEndDate,
            request.currency
        )
        
        logger.info(
            "Fetched ${stripeTransactions.size} Stripe transactions and " +
            "${ledgerTransactions.size} ledger transactions for reconciliation"
        )
        
        // Match transactions
        val matches = matchTransactions(stripeTransactions, ledgerTransactions)
        
        // Identify discrepancies
        val discrepancies = identifyDiscrepancies(
            stripeTransactions,
            ledgerTransactions,
            matches
        )
        
        // Generate summary
        val summary = generateSummary(discrepancies)
        
        logger.info(
            "Reconciliation $reconciliationId completed: " +
            "${summary.totalDiscrepancies} discrepancies found, " +
            "${matches.matchedCount} transactions matched"
        )
        
        // Persist reconciliation run
        val reconciliationRun = ReconciliationRun(
            id = reconciliationId,
            startDate = request.startDate,
            endDate = request.endDate,
            currency = request.currency,
            runAt = runAt,
            matchedTransactions = matches.matchedCount,
            totalStripeTransactions = stripeTransactions.size,
            totalLedgerTransactions = ledgerTransactions.size,
            totalDiscrepancies = summary.totalDiscrepancies,
            missingInLedgerCount = summary.missingInLedger,
            missingInStripeCount = summary.missingInStripe,
            amountMismatchesCount = summary.amountMismatches,
            currencyMismatchesCount = summary.currencyMismatches,
            createdAt = runAt
        )
        
        val savedRun = reconciliationRunRepository.save(ReconciliationRunEntity.fromDomain(reconciliationRun))
        logger.info("Persisted reconciliation run ${savedRun.id}")
        
        // Persist discrepancies
        val discrepancyEntities = discrepancies.mapIndexed { index, discrepancy ->
            ReconciliationDiscrepancyEntity.fromDomain(
                ReconciliationDiscrepancy(
                    id = UUID.randomUUID(),
                    reconciliationRunId = reconciliationId,
                    type = discrepancy.type,
                    stripeTransactionId = discrepancy.stripeTransactionId,
                    ledgerTransactionId = discrepancy.ledgerTransactionId,
                    stripeAmount = discrepancy.stripeAmount,
                    ledgerAmount = discrepancy.ledgerAmount,
                    currency = discrepancy.currency,
                    description = discrepancy.description,
                    severity = discrepancy.severity,
                    createdAt = runAt
                )
            )
        }
        
        if (discrepancyEntities.isNotEmpty()) {
            reconciliationDiscrepancyRepository.saveAll(discrepancyEntities)
            logger.info("Persisted ${discrepancyEntities.size} discrepancies for reconciliation ${savedRun.id}")
        }
        
        return ReconciliationReport(
            reconciliationId = reconciliationId,
            startDate = request.startDate,
            endDate = request.endDate,
            currency = request.currency,
            runAt = runAt,
            summary = summary,
            discrepancies = discrepancies.map { com.payments.platform.payments.api.DiscrepancyDto.fromDomain(it) },
            matchedTransactions = matches.matchedCount,
            totalStripeTransactions = stripeTransactions.size,
            totalLedgerTransactions = ledgerTransactions.size
        )
    }
    
    /**
     * Fetches Stripe balance transactions for the date range.
     */
    private fun fetchStripeBalanceTransactions(
        startDate: Instant,
        endDate: Instant,
        currency: String?
    ): List<BalanceTransaction> {
        return try {
            val currencyLower = currency?.lowercase()
            stripeService.listBalanceTransactions(startDate, endDate, currencyLower)
        } catch (e: Exception) {
            logger.error("Failed to fetch Stripe balance transactions", e)
            throw RuntimeException("Failed to fetch Stripe balance transactions: ${e.message}", e)
        }
    }
    
    /**
     * Fetches ledger transactions for the date range.
     */
    private fun fetchLedgerTransactions(
        startDate: Instant,
        endDate: Instant,
        currency: String?
    ): List<LedgerTransactionWithEntries> {
        return try {
            val response = ledgerClient.queryTransactions(startDate, endDate, currency)
            if (response.error != null) {
                throw RuntimeException("Ledger query failed: ${response.error}")
            }
            response.transactions.map { transactionWithEntries ->
                LedgerTransactionWithEntries(
                    transactionId = transactionWithEntries.transaction.transactionId,
                    referenceId = transactionWithEntries.transaction.referenceId,
                    idempotencyKey = transactionWithEntries.transaction.idempotencyKey,
                    description = transactionWithEntries.transaction.description,
                    createdAt = Instant.parse(transactionWithEntries.transaction.createdAt),
                    entries = transactionWithEntries.entries.map { entry ->
                        LedgerEntryInfo(
                            accountId = entry.accountId,
                            direction = entry.direction,
                            amountCents = entry.amountCents,
                            currency = entry.currency
                        )
                    }
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch ledger transactions", e)
            throw RuntimeException("Failed to fetch ledger transactions: ${e.message}", e)
        }
    }
    
    /**
     * Matches Stripe transactions with ledger transactions.
     * 
     * Matching priority:
     * 1. Idempotency key match (from Stripe metadata)
     * 2. Stripe ID match (reference_id in ledger)
     * 3. Amount + currency + date match (fallback)
     */
    private fun matchTransactions(
        stripeTransactions: List<BalanceTransaction>,
        ledgerTransactions: List<LedgerTransactionWithEntries>
    ): TransactionMatches {
        val matches = mutableMapOf<String, UUID>() // Stripe transaction ID -> Ledger transaction ID
        val matchedLedgerIds = mutableSetOf<UUID>()
        
        // Build index for faster lookup
        val ledgerByIdempotencyKey = ledgerTransactions.associateBy { it.idempotencyKey }
        val ledgerByReferenceId = ledgerTransactions
            .filter { it.referenceId.isNotBlank() }
            .associateBy { it.referenceId }
        
        // Match by idempotency key (highest priority)
        for (stripeTx in stripeTransactions) {
            val idempotencyKey = extractIdempotencyKey(stripeTx)
            if (idempotencyKey != null) {
                val ledgerTx = ledgerByIdempotencyKey[idempotencyKey]
                if (ledgerTx != null && !matchedLedgerIds.contains(ledgerTx.transactionId)) {
                    matches[stripeTx.id] = ledgerTx.transactionId
                    matchedLedgerIds.add(ledgerTx.transactionId)
                }
            }
        }
        
        // Match by Stripe ID (reference_id in ledger)
        for (stripeTx in stripeTransactions) {
            if (matches.containsKey(stripeTx.id)) continue // Already matched
            
            val sourceId = extractSourceId(stripeTx)
            if (sourceId != null) {
                val ledgerTx = ledgerByReferenceId[sourceId]
                if (ledgerTx != null && !matchedLedgerIds.contains(ledgerTx.transactionId)) {
                    matches[stripeTx.id] = ledgerTx.transactionId
                    matchedLedgerIds.add(ledgerTx.transactionId)
                }
            }
        }
        
        // Fallback: Match by amount + currency + date (within 1 hour window)
        for (stripeTx in stripeTransactions) {
            if (matches.containsKey(stripeTx.id)) continue // Already matched
            
            val stripeAmount = stripeTx.amount
            val stripeCurrency = stripeTx.currency?.uppercase()
            val stripeDate = Instant.ofEpochSecond(stripeTx.created)
            
            for (ledgerTx in ledgerTransactions) {
                if (matchedLedgerIds.contains(ledgerTx.transactionId)) continue // Already matched
                
                // Calculate net amount from ledger entries
                val ledgerNetAmount = calculateNetAmount(ledgerTx.entries)
                val ledgerCurrency = ledgerTx.entries.firstOrNull()?.currency
                
                // Check if amounts and currencies match (within tolerance for fees)
                val amountMatch = kotlin.math.abs(stripeAmount - ledgerNetAmount) <= 100 // Allow 1 cent tolerance
                val currencyMatch = stripeCurrency == ledgerCurrency
                val dateMatch = kotlin.math.abs(
                    stripeDate.epochSecond - ledgerTx.createdAt.epochSecond
                ) <= 3600 // Within 1 hour
                
                if (amountMatch && currencyMatch && dateMatch) {
                    matches[stripeTx.id] = ledgerTx.transactionId
                    matchedLedgerIds.add(ledgerTx.transactionId)
                    break
                }
            }
        }
        
        return TransactionMatches(matches, matchedLedgerIds)
    }
    
    /**
     * Identifies discrepancies between Stripe and ledger transactions.
     */
    private fun identifyDiscrepancies(
        stripeTransactions: List<BalanceTransaction>,
        ledgerTransactions: List<LedgerTransactionWithEntries>,
        matches: TransactionMatches
    ): List<Discrepancy> {
        val discrepancies = mutableListOf<Discrepancy>()
        
        // Find transactions missing in ledger
        for (stripeTx in stripeTransactions) {
            if (!matches.stripeToLedger.containsKey(stripeTx.id)) {
                discrepancies.add(
                    Discrepancy(
                        type = DiscrepancyType.MISSING_IN_LEDGER,
                        stripeTransactionId = stripeTx.id,
                        ledgerTransactionId = null,
                        stripeAmount = stripeTx.amount,
                        ledgerAmount = null,
                        currency = stripeTx.currency?.uppercase() ?: "UNKNOWN",
                        description = "Stripe transaction ${stripeTx.id} (${stripeTx.type}) not found in ledger. " +
                            "Amount: ${stripeTx.amount / 100.0} ${stripeTx.currency?.uppercase()}",
                        severity = DiscrepancySeverity.CRITICAL
                    )
                )
            } else {
                // Check for amount/currency mismatches
                val ledgerTxId = matches.stripeToLedger[stripeTx.id]!!
                val ledgerTx = ledgerTransactions.find { it.transactionId == ledgerTxId }
                
                if (ledgerTx != null) {
                    val ledgerNetAmount = calculateNetAmount(ledgerTx.entries)
                    val ledgerCurrency = ledgerTx.entries.firstOrNull()?.currency
                    val stripeCurrency = stripeTx.currency?.uppercase()
                    
                    // Check amount mismatch (allow small tolerance for fees)
                    if (kotlin.math.abs(stripeTx.amount - ledgerNetAmount) > 100) {
                        discrepancies.add(
                            Discrepancy(
                                type = DiscrepancyType.AMOUNT_MISMATCH,
                                stripeTransactionId = stripeTx.id,
                                ledgerTransactionId = ledgerTxId,
                                stripeAmount = stripeTx.amount,
                                ledgerAmount = ledgerNetAmount,
                                currency = stripeCurrency ?: ledgerCurrency ?: "UNKNOWN",
                                description = "Amount mismatch: Stripe=${stripeTx.amount / 100.0} ${stripeCurrency}, " +
                                    "Ledger=${ledgerNetAmount / 100.0} ${ledgerCurrency}",
                                severity = DiscrepancySeverity.HIGH
                            )
                        )
                    }
                    
                    // Check currency mismatch
                    if (stripeCurrency != null && ledgerCurrency != null && stripeCurrency != ledgerCurrency) {
                        discrepancies.add(
                            Discrepancy(
                                type = DiscrepancyType.CURRENCY_MISMATCH,
                                stripeTransactionId = stripeTx.id,
                                ledgerTransactionId = ledgerTxId,
                                stripeAmount = stripeTx.amount,
                                ledgerAmount = ledgerNetAmount,
                                currency = "$stripeCurrency vs $ledgerCurrency",
                                description = "Currency mismatch: Stripe=$stripeCurrency, Ledger=$ledgerCurrency",
                                severity = DiscrepancySeverity.MEDIUM
                            )
                        )
                    }
                }
            }
        }
        
        // Find transactions missing in Stripe
        for (ledgerTx in ledgerTransactions) {
            if (!matches.matchedLedgerIds.contains(ledgerTx.transactionId)) {
                val ledgerNetAmount = calculateNetAmount(ledgerTx.entries)
                val ledgerCurrency = ledgerTx.entries.firstOrNull()?.currency ?: "UNKNOWN"
                
                discrepancies.add(
                    Discrepancy(
                        type = DiscrepancyType.MISSING_IN_STRIPE,
                        stripeTransactionId = null,
                        ledgerTransactionId = ledgerTx.transactionId,
                        stripeAmount = null,
                        ledgerAmount = ledgerNetAmount,
                        currency = ledgerCurrency,
                        description = "Ledger transaction ${ledgerTx.transactionId} (ref: ${ledgerTx.referenceId}) " +
                            "not found in Stripe. Amount: ${ledgerNetAmount / 100.0} $ledgerCurrency",
                        severity = DiscrepancySeverity.CRITICAL
                    )
                )
            }
        }
        
        return discrepancies
    }
    
    /**
     * Gets a reconciliation run by ID.
     * 
     * @param reconciliationId The reconciliation run ID
     * @return Reconciliation run with its discrepancies, or null if not found
     */
    fun getReconciliationRun(reconciliationId: UUID): ReconciliationRunWithDiscrepancies? {
        val runEntity = reconciliationRunRepository.findById(reconciliationId).orElse(null)
            ?: return null
        
        val discrepancyEntities = reconciliationDiscrepancyRepository.findByReconciliationRunId(reconciliationId)
        
        return ReconciliationRunWithDiscrepancies(
            run = runEntity.toDomain(),
            discrepancies = discrepancyEntities.map { it.toDomain() }
        )
    }
    
    /**
     * Gets recent reconciliation runs.
     * 
     * @param limit Maximum number of runs to return (default: 10)
     * @return List of recent reconciliation runs
     */
    fun getRecentReconciliationRuns(limit: Int = 10): List<ReconciliationRun> {
        return reconciliationRunRepository.findTop10ByOrderByRunAtDesc()
            .take(limit)
            .map { it.toDomain() }
    }
    
    /**
     * Gets reconciliation runs by date range.
     * 
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @return List of reconciliation runs in the date range
     */
    fun getReconciliationRunsByDateRange(startDate: Instant, endDate: Instant): List<ReconciliationRun> {
        return reconciliationRunRepository.findByRunAtBetween(startDate, endDate)
            .map { it.toDomain() }
    }
    
    /**
     * Generates summary from discrepancies.
     */
    private fun generateSummary(discrepancies: List<Discrepancy>): ReconciliationSummary {
        return ReconciliationSummary(
            totalDiscrepancies = discrepancies.size,
            missingInLedger = discrepancies.count { it.type == DiscrepancyType.MISSING_IN_LEDGER },
            missingInStripe = discrepancies.count { it.type == DiscrepancyType.MISSING_IN_STRIPE },
            amountMismatches = discrepancies.count { it.type == DiscrepancyType.AMOUNT_MISMATCH },
            currencyMismatches = discrepancies.count { it.type == DiscrepancyType.CURRENCY_MISMATCH }
        )
    }
    
    /**
     * Extracts idempotency key from Stripe balance transaction metadata.
     */
    private fun extractIdempotencyKey(stripeTx: BalanceTransaction): String? {
        // Try to get idempotency key from source object metadata
        val source = stripeTx.sourceObject
        if (source is com.stripe.model.PaymentIntent) {
            return source.metadata["idempotencyKey"]
        } else if (source is com.stripe.model.Refund) {
            return source.metadata["idempotencyKey"]
        } else if (source is com.stripe.model.Transfer) {
            return source.metadata["idempotencyKey"]
        } else if (source is com.stripe.model.Dispute) {
            return source.metadata["idempotencyKey"]
        }
        return null
    }
    
    /**
     * Extracts source ID from Stripe balance transaction.
     */
    private fun extractSourceId(stripeTx: BalanceTransaction): String? {
        return when (stripeTx.type) {
            "charge", "payment" -> {
                // For charges, the source is the charge ID or payment intent ID
                stripeTx.source
            }
            "refund" -> {
                // For refunds, try to get the refund ID
                val source = stripeTx.sourceObject
                if (source is com.stripe.model.Refund) {
                    source.id
                } else {
                    stripeTx.source
                }
            }
            "transfer" -> {
                // For transfers, get the transfer ID
                val source = stripeTx.sourceObject
                if (source is com.stripe.model.Transfer) {
                    source.id
                } else {
                    stripeTx.source
                }
            }
            "adjustment", "application_fee", "application_fee_refund" -> {
                stripeTx.source
            }
            "dispute" -> {
                // For disputes, get the dispute ID
                val source = stripeTx.sourceObject
                if (source is com.stripe.model.Dispute) {
                    source.id
                } else {
                    stripeTx.source
                }
            }
            else -> stripeTx.source
        }
    }
    
    /**
     * Calculates net amount from ledger entries.
     * Net = sum of DEBIT entries - sum of CREDIT entries
     */
    private fun calculateNetAmount(entries: List<LedgerEntryInfo>): Long {
        var net = 0L
        for (entry in entries) {
            when (entry.direction) {
                "DEBIT" -> net += entry.amountCents
                "CREDIT" -> net -= entry.amountCents
            }
        }
        return net
    }
    
    /**
     * Internal data class for transaction matches.
     */
    private data class TransactionMatches(
        val stripeToLedger: Map<String, UUID>,
        val matchedLedgerIds: Set<UUID>
    ) {
        val matchedCount: Int
            get() = stripeToLedger.size
    }
    
    /**
     * Internal data class for ledger transaction with entries.
     */
    private data class LedgerTransactionWithEntries(
        val transactionId: UUID,
        val referenceId: String,
        val idempotencyKey: String,
        val description: String,
        val createdAt: Instant,
        val entries: List<LedgerEntryInfo>
    )
    
    /**
     * Internal data class for ledger entry info.
     */
    private data class LedgerEntryInfo(
        val accountId: UUID,
        val direction: String,
        val amountCents: Long,
        val currency: String
    )
    
    /**
     * Data class for reconciliation run with its discrepancies.
     */
    data class ReconciliationRunWithDiscrepancies(
        val run: ReconciliationRun,
        val discrepancies: List<ReconciliationDiscrepancy>
    )
}

