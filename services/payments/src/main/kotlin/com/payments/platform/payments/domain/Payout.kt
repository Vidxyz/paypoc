package com.payments.platform.payments.domain

import java.time.Instant
import java.util.UUID

/**
 * Payout domain entity.
 * 
 * Represents a transfer of funds from platform account to seller account.
 * 
 * Important: This stores workflow state ONLY.
 * - amount_cents is metadata, not balance
 * - ledger_transaction_id is a reference (NULL until payout confirmed), not truth
 * - state is workflow-only
 * 
 * If this database is deleted, money is still correct in the ledger.
 */
data class Payout(
    val id: UUID,
    val sellerId: String,
    val amountCents: Long,
    val currency: String,
    val state: PayoutState,
    val stripeTransferId: String?,
    val ledgerTransactionId: UUID?,  // NULL until payout confirmed
    val idempotencyKey: String,
    val description: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val failureReason: String?
)

