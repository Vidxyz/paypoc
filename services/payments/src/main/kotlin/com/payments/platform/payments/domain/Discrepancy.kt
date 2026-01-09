package com.payments.platform.payments.domain

import java.util.UUID

/**
 * Types of discrepancies found during reconciliation.
 */
enum class DiscrepancyType {
    /**
     * Transaction exists in Stripe but not in ledger.
     * Most critical - indicates webhook failure or processing error.
     */
    MISSING_IN_LEDGER,
    
    /**
     * Transaction exists in ledger but not in Stripe.
     * Shouldn't happen (ledger only writes after Stripe confirms).
     * Indicates data integrity issue.
     */
    MISSING_IN_STRIPE,
    
    /**
     * Both exist but amounts don't match.
     * Could be due to fee calculation differences or currency conversion issues.
     */
    AMOUNT_MISMATCH,
    
    /**
     * Both exist but currencies don't match.
     * Indicates configuration error.
     */
    CURRENCY_MISMATCH
}

/**
 * Severity of a discrepancy.
 */
enum class DiscrepancySeverity {
    /**
     * Critical - requires immediate attention (e.g., missing in ledger).
     */
    CRITICAL,
    
    /**
     * High - significant issue that needs investigation (e.g., amount mismatch).
     */
    HIGH,
    
    /**
     * Medium - moderate issue (e.g., currency mismatch).
     */
    MEDIUM,
    
    /**
     * Low - minor issue or informational.
     */
    LOW
}

/**
 * A discrepancy found during reconciliation.
 */
data class Discrepancy(
    val type: DiscrepancyType,
    val stripeTransactionId: String?,
    val ledgerTransactionId: UUID?,
    val stripeAmount: Long?,
    val ledgerAmount: Long?,
    val currency: String,
    val description: String,
    val severity: DiscrepancySeverity
) {
    companion object {
        /**
         * Determines severity based on discrepancy type.
         */
        fun determineSeverity(type: DiscrepancyType): DiscrepancySeverity {
            return when (type) {
                DiscrepancyType.MISSING_IN_LEDGER -> DiscrepancySeverity.CRITICAL
                DiscrepancyType.MISSING_IN_STRIPE -> DiscrepancySeverity.CRITICAL
                DiscrepancyType.AMOUNT_MISMATCH -> DiscrepancySeverity.HIGH
                DiscrepancyType.CURRENCY_MISMATCH -> DiscrepancySeverity.MEDIUM
            }
        }
    }
}

