package com.payments.platform.payments.domain

import java.time.Instant
import java.util.UUID

/**
 * Domain model for a reconciliation discrepancy.
 */
data class ReconciliationDiscrepancy(
    val id: UUID,
    val reconciliationRunId: UUID,
    val type: DiscrepancyType,
    val stripeTransactionId: String?,
    val ledgerTransactionId: UUID?,
    val stripeAmount: Long?,
    val ledgerAmount: Long?,
    val currency: String,
    val description: String,
    val severity: DiscrepancySeverity,
    val createdAt: Instant
)

