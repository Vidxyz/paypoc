package com.payments.platform.payments.domain

import java.time.Instant
import java.util.UUID

/**
 * Domain model for a reconciliation run.
 */
data class ReconciliationRun(
    val id: UUID,
    val startDate: Instant,
    val endDate: Instant,
    val currency: String?,
    val runAt: Instant,
    val matchedTransactions: Int,
    val totalStripeTransactions: Int,
    val totalLedgerTransactions: Int,
    val totalDiscrepancies: Int,
    val missingInLedgerCount: Int,
    val missingInStripeCount: Int,
    val amountMismatchesCount: Int,
    val currencyMismatchesCount: Int,
    val createdAt: Instant
)

