package com.payments.platform.payments.domain

import java.time.Instant
import java.util.UUID

/**
 * Payment domain entity.
 * 
 * Important: This stores workflow state ONLY.
 * - amount_cents is metadata, not balance
 * - ledger_transaction_id is a reference, not truth
 * - state is workflow-only
 * 
 * If this database is deleted, money is still correct in the ledger.
 */
data class Payment(
    val id: UUID,
    val amountCents: Long,
    val currency: String,
    val state: PaymentState,
    val ledgerTransactionId: UUID,
    val idempotencyKey: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

