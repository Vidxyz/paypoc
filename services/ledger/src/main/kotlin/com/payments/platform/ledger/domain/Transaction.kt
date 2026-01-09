package com.payments.platform.ledger.domain

import java.time.Instant
import java.util.UUID

/**
 * Ledger transaction - metadata for a financial transaction.
 * 
 * Used for:
 * - Idempotency checks (via idempotency_key)
 * - Auditing
 * - Reconciliation
 * - Linking money movement to external references (e.g., Stripe paymentIntent ID)
 */
data class Transaction(
    val id: UUID,
    val referenceId: String,  // External reference (e.g., Stripe paymentIntent ID)
    val idempotencyKey: String,
    val description: String,
    val createdAt: Instant
)

/**
 * Ledger entry - a single DEBIT or CREDIT entry in double-entry bookkeeping.
 * 
 * Multiple entries form a transaction, and must balance (sum of debits = sum of credits).
 */
data class LedgerEntry(
    val id: UUID,
    val transactionId: UUID,
    val accountId: UUID,
    val direction: EntryDirection,
    val amountCents: Long,  // Always positive, direction indicates DEBIT/CREDIT
    val currency: String,
    val createdAt: Instant
)

enum class EntryDirection {
    DEBIT,
    CREDIT
}

/**
 * Request to create a double-entry transaction.
 * 
 * All entries must balance: sum of debits = sum of credits.
 */
data class CreateDoubleEntryTransactionRequest(
    val referenceId: String,  // External reference (e.g., Stripe paymentIntent ID)
    val idempotencyKey: String,
    val description: String,
    val entries: List<EntryRequest>
)

/**
 * Request for a single ledger entry.
 */
data class EntryRequest(
    val accountId: UUID,
    val direction: EntryDirection,
    val amountCents: Long,  // Must be positive
    val currency: String
)

/**
 * Transaction with its entries - used for reconciliation queries.
 */
data class TransactionWithEntries(
    val transaction: Transaction,
    val entries: List<LedgerEntry>
)
