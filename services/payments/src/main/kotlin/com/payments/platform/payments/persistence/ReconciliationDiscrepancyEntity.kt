package com.payments.platform.payments.persistence

import com.payments.platform.payments.domain.DiscrepancySeverity
import com.payments.platform.payments.domain.DiscrepancyType
import com.payments.platform.payments.domain.ReconciliationDiscrepancy
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for ReconciliationDiscrepancy.
 * Maps to the reconciliation_discrepancies table in the database.
 */
@Entity
@Table(name = "reconciliation_discrepancies")
class ReconciliationDiscrepancyEntity(
    @Id
    @Column(name = "id")
    val id: UUID,
    
    @Column(name = "reconciliation_run_id", nullable = false)
    val reconciliationRunId: UUID,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    val type: DiscrepancyType,
    
    @Column(name = "stripe_transaction_id")
    val stripeTransactionId: String?,
    
    @Column(name = "ledger_transaction_id")
    val ledgerTransactionId: UUID?,
    
    @Column(name = "stripe_amount")
    val stripeAmount: Long?,
    
    @Column(name = "ledger_amount")
    val ledgerAmount: Long?,
    
    @Column(name = "currency", nullable = false)
    val currency: String,
    
    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    val description: String,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    val severity: DiscrepancySeverity,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant
) {
    // JPA requires no-arg constructor
    constructor() : this(
        id = UUID.randomUUID(),
        reconciliationRunId = UUID.randomUUID(),
        type = DiscrepancyType.MISSING_IN_LEDGER,
        stripeTransactionId = null,
        ledgerTransactionId = null,
        stripeAmount = null,
        ledgerAmount = null,
        currency = "USD",
        description = "",
        severity = DiscrepancySeverity.CRITICAL,
        createdAt = Instant.now()
    )
    
    fun toDomain(): ReconciliationDiscrepancy {
        return ReconciliationDiscrepancy(
            id = id,
            reconciliationRunId = reconciliationRunId,
            type = type,
            stripeTransactionId = stripeTransactionId,
            ledgerTransactionId = ledgerTransactionId,
            stripeAmount = stripeAmount,
            ledgerAmount = ledgerAmount,
            currency = currency,
            description = description,
            severity = severity,
            createdAt = createdAt
        )
    }
    
    companion object {
        fun fromDomain(discrepancy: ReconciliationDiscrepancy): ReconciliationDiscrepancyEntity {
            return ReconciliationDiscrepancyEntity(
                id = discrepancy.id,
                reconciliationRunId = discrepancy.reconciliationRunId,
                type = discrepancy.type,
                stripeTransactionId = discrepancy.stripeTransactionId,
                ledgerTransactionId = discrepancy.ledgerTransactionId,
                stripeAmount = discrepancy.stripeAmount,
                ledgerAmount = discrepancy.ledgerAmount,
                currency = discrepancy.currency,
                description = discrepancy.description,
                severity = discrepancy.severity,
                createdAt = discrepancy.createdAt
            )
        }
    }
}

