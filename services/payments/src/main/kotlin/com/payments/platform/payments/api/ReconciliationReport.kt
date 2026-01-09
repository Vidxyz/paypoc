package com.payments.platform.payments.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.payments.platform.payments.domain.Discrepancy
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * Reconciliation report containing summary and discrepancies.
 */
@Schema(description = "Reconciliation report")
data class ReconciliationReport(
    @JsonProperty("reconciliationId")
    @Schema(description = "Unique reconciliation run ID", example = "550e8400-e29b-41d4-a716-446655440000")
    val reconciliationId: UUID,
    
    @JsonProperty("startDate")
    @Schema(description = "Start date of reconciliation window", example = "2024-01-15T00:00:00Z")
    val startDate: Instant,
    
    @JsonProperty("endDate")
    @Schema(description = "End date of reconciliation window", example = "2024-01-16T00:00:00Z")
    val endDate: Instant,
    
    @JsonProperty("currency")
    @Schema(description = "Currency filter applied (if any)", example = "USD")
    val currency: String?,
    
    @JsonProperty("runAt")
    @Schema(description = "When reconciliation was run", example = "2024-01-16T10:30:00Z")
    val runAt: Instant,
    
    @JsonProperty("summary")
    @Schema(description = "Reconciliation summary")
    val summary: ReconciliationSummary,
    
    @JsonProperty("discrepancies")
    @Schema(description = "List of discrepancies found")
    val discrepancies: List<DiscrepancyDto>,
    
    @JsonProperty("matchedTransactions")
    @Schema(description = "Number of transactions that matched between Stripe and ledger")
    val matchedTransactions: Int,
    
    @JsonProperty("totalStripeTransactions")
    @Schema(description = "Total number of Stripe balance transactions in the date range")
    val totalStripeTransactions: Int,
    
    @JsonProperty("totalLedgerTransactions")
    @Schema(description = "Total number of ledger transactions in the date range")
    val totalLedgerTransactions: Int
)

/**
 * Summary of reconciliation results.
 */
@Schema(description = "Reconciliation summary")
data class ReconciliationSummary(
    @JsonProperty("totalDiscrepancies")
    @Schema(description = "Total number of discrepancies found")
    val totalDiscrepancies: Int,
    
    @JsonProperty("missingInLedger")
    @Schema(description = "Number of transactions missing in ledger")
    val missingInLedger: Int,
    
    @JsonProperty("missingInStripe")
    @Schema(description = "Number of transactions missing in Stripe")
    val missingInStripe: Int,
    
    @JsonProperty("amountMismatches")
    @Schema(description = "Number of amount mismatches")
    val amountMismatches: Int,
    
    @JsonProperty("currencyMismatches")
    @Schema(description = "Number of currency mismatches")
    val currencyMismatches: Int
)

/**
 * DTO for discrepancy (for API response).
 */
@Schema(description = "Discrepancy")
data class DiscrepancyDto(
    @JsonProperty("type")
    @Schema(description = "Type of discrepancy", example = "MISSING_IN_LEDGER")
    val type: String,
    
    @JsonProperty("stripeTransactionId")
    @Schema(description = "Stripe transaction ID (if applicable)", example = "txn_1234567890")
    val stripeTransactionId: String?,
    
    @JsonProperty("ledgerTransactionId")
    @Schema(description = "Ledger transaction ID (if applicable)", example = "550e8400-e29b-41d4-a716-446655440000")
    val ledgerTransactionId: UUID?,
    
    @JsonProperty("stripeAmount")
    @Schema(description = "Amount from Stripe (in cents)", example = "10000")
    val stripeAmount: Long?,
    
    @JsonProperty("ledgerAmount")
    @Schema(description = "Amount from ledger (in cents)", example = "10000")
    val ledgerAmount: Long?,
    
    @JsonProperty("currency")
    @Schema(description = "Currency code", example = "USD")
    val currency: String,
    
    @JsonProperty("description")
    @Schema(description = "Description of the discrepancy", example = "Transaction missing in ledger")
    val description: String,
    
    @JsonProperty("severity")
    @Schema(description = "Severity of the discrepancy", example = "CRITICAL")
    val severity: String
) {
    companion object {
        fun fromDomain(discrepancy: Discrepancy): DiscrepancyDto {
            return DiscrepancyDto(
                type = discrepancy.type.name,
                stripeTransactionId = discrepancy.stripeTransactionId,
                ledgerTransactionId = discrepancy.ledgerTransactionId,
                stripeAmount = discrepancy.stripeAmount,
                ledgerAmount = discrepancy.ledgerAmount,
                currency = discrepancy.currency,
                description = discrepancy.description,
                severity = discrepancy.severity.name
            )
        }
    }
}

