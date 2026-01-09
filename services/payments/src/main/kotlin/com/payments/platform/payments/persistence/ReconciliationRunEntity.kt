package com.payments.platform.payments.persistence

import com.payments.platform.payments.domain.ReconciliationRun
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for ReconciliationRun.
 * Maps to the reconciliation_runs table in the database.
 */
@Entity
@Table(name = "reconciliation_runs")
class ReconciliationRunEntity(
    @Id
    @Column(name = "id")
    val id: UUID,
    
    @Column(name = "start_date", nullable = false)
    val startDate: Instant,
    
    @Column(name = "end_date", nullable = false)
    val endDate: Instant,
    
    @Column(name = "currency")
    val currency: String?,
    
    @Column(name = "run_at", nullable = false)
    val runAt: Instant,
    
    @Column(name = "matched_transactions", nullable = false)
    val matchedTransactions: Int,
    
    @Column(name = "total_stripe_transactions", nullable = false)
    val totalStripeTransactions: Int,
    
    @Column(name = "total_ledger_transactions", nullable = false)
    val totalLedgerTransactions: Int,
    
    @Column(name = "total_discrepancies", nullable = false)
    val totalDiscrepancies: Int,
    
    @Column(name = "missing_in_ledger_count", nullable = false)
    val missingInLedgerCount: Int,
    
    @Column(name = "missing_in_stripe_count", nullable = false)
    val missingInStripeCount: Int,
    
    @Column(name = "amount_mismatches_count", nullable = false)
    val amountMismatchesCount: Int,
    
    @Column(name = "currency_mismatches_count", nullable = false)
    val currencyMismatchesCount: Int,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant
) {
    // JPA requires no-arg constructor
    constructor() : this(
        id = UUID.randomUUID(),
        startDate = Instant.now(),
        endDate = Instant.now(),
        currency = null,
        runAt = Instant.now(),
        matchedTransactions = 0,
        totalStripeTransactions = 0,
        totalLedgerTransactions = 0,
        totalDiscrepancies = 0,
        missingInLedgerCount = 0,
        missingInStripeCount = 0,
        amountMismatchesCount = 0,
        currencyMismatchesCount = 0,
        createdAt = Instant.now()
    )
    
    fun toDomain(): ReconciliationRun {
        return ReconciliationRun(
            id = id,
            startDate = startDate,
            endDate = endDate,
            currency = currency,
            runAt = runAt,
            matchedTransactions = matchedTransactions,
            totalStripeTransactions = totalStripeTransactions,
            totalLedgerTransactions = totalLedgerTransactions,
            totalDiscrepancies = totalDiscrepancies,
            missingInLedgerCount = missingInLedgerCount,
            missingInStripeCount = missingInStripeCount,
            amountMismatchesCount = amountMismatchesCount,
            currencyMismatchesCount = currencyMismatchesCount,
            createdAt = createdAt
        )
    }
    
    companion object {
        fun fromDomain(run: ReconciliationRun): ReconciliationRunEntity {
            return ReconciliationRunEntity(
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
    }
}

