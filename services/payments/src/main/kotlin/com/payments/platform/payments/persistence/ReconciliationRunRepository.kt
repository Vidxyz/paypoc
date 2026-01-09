package com.payments.platform.payments.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Repository for ReconciliationRun entities.
 */
@Repository
interface ReconciliationRunRepository : JpaRepository<ReconciliationRunEntity, UUID> {
    /**
     * Finds reconciliation runs by date range.
     */
    fun findByRunAtBetween(startDate: Instant, endDate: Instant): List<ReconciliationRunEntity>
    
    /**
     * Finds reconciliation runs by currency.
     */
    fun findByCurrency(currency: String): List<ReconciliationRunEntity>
    
    /**
     * Finds reconciliation runs ordered by run date (most recent first).
     */
    fun findTop10ByOrderByRunAtDesc(): List<ReconciliationRunEntity>
}

