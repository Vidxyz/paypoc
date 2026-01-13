package com.payments.platform.payments.api

import com.payments.platform.payments.service.ReconciliationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

/**
 * Controller for reconciliation operations.
 * 
 * Reconciliation compares Stripe's financial records with our internal ledger
 * to ensure books are balanced and identify discrepancies.
 */
@RestController
@RequestMapping("/reconciliation")
@Tag(name = "Reconciliation", description = "Reconciliation API - Compare Stripe records with ledger")
class ReconciliationController(
    private val reconciliationService: ReconciliationService
) {
    /**
     * POST /reconciliation/run
     * Runs reconciliation for the specified date range.
     * 
     * This endpoint compares Stripe balance transactions with ledger transactions
     * and generates a detailed report of any discrepancies.
     * 
     * @param request Reconciliation request with date range and optional currency filter
     * @return Reconciliation report with summary and discrepancies
     */
    @Operation(
        summary = "Run reconciliation",
        description = "Compares Stripe balance transactions with ledger transactions for the specified date range. " +
            "Returns a detailed report of matched transactions and any discrepancies found. " +
            "Discrepancies are report-only and require manual review."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Reconciliation completed successfully",
                content = [Content(schema = Schema(implementation = ReconciliationReport::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid request (e.g., invalid date range)",
                content = [Content(schema = Schema(implementation = ReconciliationReport::class))]
            ),
            ApiResponse(
                responseCode = "500",
                description = "Internal server error (e.g., Stripe API failure, ledger service unavailable)"
            )
        ]
    )
    @PostMapping("/run")
    fun runReconciliation(
        @Valid @RequestBody request: ReconciliationRequest
    ): ResponseEntity<ReconciliationReport> {
        // todo-vh: There are still some false positives in reconciliation with ledger entries being 0 and stripe entries not existing in ledger. Need to investigate and fix.
        // Additionally, it seems our ledger enters stripe clearing but stripe witholds platform fee. Need to account for this
        return try {
            val report = reconciliationService.runReconciliation(request)
            ResponseEntity.ok(report)
        } catch (e: IllegalArgumentException) {
            // Invalid request (e.g., invalid date range)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ReconciliationReport(
                    reconciliationId = java.util.UUID.randomUUID(),
                    startDate = request.startDate,
                    endDate = request.endDate,
                    currency = request.currency,
                    runAt = java.time.Instant.now(),
                    summary = ReconciliationSummary(
                        totalDiscrepancies = 0,
                        missingInLedger = 0,
                        missingInStripe = 0,
                        amountMismatches = 0,
                        currencyMismatches = 0
                    ),
                    discrepancies = emptyList(),
                    matchedTransactions = 0,
                    totalStripeTransactions = 0,
                    totalLedgerTransactions = 0
                ).apply {
                    // Note: This is a workaround - we can't easily add error field to report
                    // In production, consider a separate error response DTO
                }
            )
        } catch (e: RuntimeException) {
            // Service errors (Stripe API failure, ledger service unavailable, etc.)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }
    
    /**
     * GET /reconciliation/runs/{reconciliationId}
     * Gets a reconciliation run by ID with its discrepancies.
     * 
     * @param reconciliationId The reconciliation run ID
     * @return Reconciliation run with discrepancies, or 404 if not found
     */
    @Operation(
        summary = "Get reconciliation run by ID",
        description = "Retrieves a reconciliation run by ID along with all its discrepancies. " +
            "Useful for reviewing past reconciliation results and audit trail."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Reconciliation run found",
                content = [Content(schema = Schema(implementation = ReconciliationRunWithDiscrepanciesResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Reconciliation run not found"
            )
        ]
    )
    @GetMapping("/runs/{reconciliationId}")
    fun getReconciliationRun(
        @PathVariable reconciliationId: UUID
    ): ResponseEntity<ReconciliationRunWithDiscrepanciesResponse> {
        val runWithDiscrepancies = reconciliationService.getReconciliationRun(reconciliationId)
            ?: return ResponseEntity.notFound().build()
        
        return ResponseEntity.ok(
            ReconciliationRunWithDiscrepanciesResponse(
                run = ReconciliationRunResponse(
                    id = runWithDiscrepancies.run.id,
                    startDate = runWithDiscrepancies.run.startDate,
                    endDate = runWithDiscrepancies.run.endDate,
                    currency = runWithDiscrepancies.run.currency,
                    runAt = runWithDiscrepancies.run.runAt,
                    matchedTransactions = runWithDiscrepancies.run.matchedTransactions,
                    totalStripeTransactions = runWithDiscrepancies.run.totalStripeTransactions,
                    totalLedgerTransactions = runWithDiscrepancies.run.totalLedgerTransactions,
                    totalDiscrepancies = runWithDiscrepancies.run.totalDiscrepancies,
                    missingInLedgerCount = runWithDiscrepancies.run.missingInLedgerCount,
                    missingInStripeCount = runWithDiscrepancies.run.missingInStripeCount,
                    amountMismatchesCount = runWithDiscrepancies.run.amountMismatchesCount,
                    currencyMismatchesCount = runWithDiscrepancies.run.currencyMismatchesCount,
                    createdAt = runWithDiscrepancies.run.createdAt
                ),
                discrepancies = runWithDiscrepancies.discrepancies.map { discrepancy ->
                    ReconciliationDiscrepancyResponse(
                        id = discrepancy.id,
                        reconciliationRunId = discrepancy.reconciliationRunId,
                        type = discrepancy.type.name,
                        stripeTransactionId = discrepancy.stripeTransactionId,
                        ledgerTransactionId = discrepancy.ledgerTransactionId,
                        stripeAmount = discrepancy.stripeAmount,
                        ledgerAmount = discrepancy.ledgerAmount,
                        currency = discrepancy.currency,
                        description = discrepancy.description,
                        severity = discrepancy.severity.name,
                        createdAt = discrepancy.createdAt
                    )
                }
            )
        )
    }
    
    /**
     * GET /reconciliation/runs
     * Gets recent reconciliation runs.
     * 
     * @param limit Maximum number of runs to return (default: 10, max: 100)
     * @return List of recent reconciliation runs
     */
    @Operation(
        summary = "Get recent reconciliation runs",
        description = "Retrieves the most recent reconciliation runs, ordered by run date (most recent first). " +
            "Useful for viewing reconciliation history and audit trail."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "List of recent reconciliation runs",
                content = [Content(schema = Schema(implementation = Array<ReconciliationRunResponse>::class))]
            )
        ]
    )
    @GetMapping("/runs")
    fun getRecentReconciliationRuns(
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<List<ReconciliationRunResponse>> {
        val maxLimit = minOf(limit, 100) // Cap at 100
        val runs = reconciliationService.getRecentReconciliationRuns(maxLimit)
        
        return ResponseEntity.ok(
            runs.map { run ->
                ReconciliationRunResponse(
                    id = run.id,
                    startDate = run.startDate,
                    endDate = run.endDate,
                    currency = run.currency,
                    runAt = run.runAt,
                    matchedTransactions = run.matchedTransactions,
                    totalStripeTransactions = run.totalStripeTransactions,
                    totalLedgerTransactions = run.totalLedgerTransactions,
                    totalDiscrepancies = run.totalDiscrepancies,
                    missingInLedgerCount = run.missingInLedgerCount,
                    missingInStripeCount = run.missingInStripeCount,
                    amountMismatchesCount = run.amountMismatchesCount,
                    currencyMismatchesCount = run.currencyMismatchesCount,
                    createdAt = run.createdAt
                )
            }
        )
    }
}

