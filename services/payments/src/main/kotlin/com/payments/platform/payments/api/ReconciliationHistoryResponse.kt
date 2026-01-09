package com.payments.platform.payments.api

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * Response DTO for reconciliation run with discrepancies.
 */
@Schema(description = "Reconciliation run with discrepancies")
data class ReconciliationRunResponse(
    @JsonProperty("id")
    @Schema(description = "Reconciliation run ID", example = "550e8400-e29b-41d4-a716-446655440000")
    val id: UUID,
    
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
    
    @JsonProperty("matchedTransactions")
    @Schema(description = "Number of transactions that matched", example = "150")
    val matchedTransactions: Int,
    
    @JsonProperty("totalStripeTransactions")
    @Schema(description = "Total Stripe transactions", example = "155")
    val totalStripeTransactions: Int,
    
    @JsonProperty("totalLedgerTransactions")
    @Schema(description = "Total ledger transactions", example = "150")
    val totalLedgerTransactions: Int,
    
    @JsonProperty("totalDiscrepancies")
    @Schema(description = "Total discrepancies found", example = "5")
    val totalDiscrepancies: Int,
    
    @JsonProperty("missingInLedgerCount")
    @Schema(description = "Number of transactions missing in ledger", example = "3")
    val missingInLedgerCount: Int,
    
    @JsonProperty("missingInStripeCount")
    @Schema(description = "Number of transactions missing in Stripe", example = "0")
    val missingInStripeCount: Int,
    
    @JsonProperty("amountMismatchesCount")
    @Schema(description = "Number of amount mismatches", example = "2")
    val amountMismatchesCount: Int,
    
    @JsonProperty("currencyMismatchesCount")
    @Schema(description = "Number of currency mismatches", example = "0")
    val currencyMismatchesCount: Int,
    
    @JsonProperty("createdAt")
    @Schema(description = "When the reconciliation run was created", example = "2024-01-16T10:30:00Z")
    val createdAt: Instant
)

/**
 * Response DTO for reconciliation discrepancy.
 */
@Schema(description = "Reconciliation discrepancy")
data class ReconciliationDiscrepancyResponse(
    @JsonProperty("id")
    @Schema(description = "Discrepancy ID", example = "660e8400-e29b-41d4-a716-446655440000")
    val id: UUID,
    
    @JsonProperty("reconciliationRunId")
    @Schema(description = "Reconciliation run ID", example = "550e8400-e29b-41d4-a716-446655440000")
    val reconciliationRunId: UUID,
    
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
    val severity: String,
    
    @JsonProperty("createdAt")
    @Schema(description = "When the discrepancy was created", example = "2024-01-16T10:30:00Z")
    val createdAt: Instant
)

/**
 * Response DTO for reconciliation run with its discrepancies.
 */
@Schema(description = "Reconciliation run with discrepancies")
data class ReconciliationRunWithDiscrepanciesResponse(
    @JsonProperty("run")
    @Schema(description = "Reconciliation run details")
    val run: ReconciliationRunResponse,
    
    @JsonProperty("discrepancies")
    @Schema(description = "List of discrepancies for this run")
    val discrepancies: List<ReconciliationDiscrepancyResponse>
)

