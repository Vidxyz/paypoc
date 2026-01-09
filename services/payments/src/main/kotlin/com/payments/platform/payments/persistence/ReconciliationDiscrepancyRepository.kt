package com.payments.platform.payments.persistence

import com.payments.platform.payments.domain.DiscrepancySeverity
import com.payments.platform.payments.domain.DiscrepancyType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for ReconciliationDiscrepancy entities.
 */
@Repository
interface ReconciliationDiscrepancyRepository : JpaRepository<ReconciliationDiscrepancyEntity, UUID> {
    /**
     * Finds all discrepancies for a reconciliation run.
     */
    fun findByReconciliationRunId(reconciliationRunId: UUID): List<ReconciliationDiscrepancyEntity>
    
    /**
     * Finds discrepancies by type.
     */
    fun findByType(type: DiscrepancyType): List<ReconciliationDiscrepancyEntity>
    
    /**
     * Finds discrepancies by severity.
     */
    fun findBySeverity(severity: DiscrepancySeverity): List<ReconciliationDiscrepancyEntity>
    
    /**
     * Finds discrepancies by reconciliation run ID and severity.
     */
    fun findByReconciliationRunIdAndSeverity(
        reconciliationRunId: UUID,
        severity: DiscrepancySeverity
    ): List<ReconciliationDiscrepancyEntity>
}

