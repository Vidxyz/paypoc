package com.payments.platform.inventory.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface InventoryTransactionRepository : JpaRepository<InventoryTransactionEntity, UUID> {
    fun findByInventoryId(inventoryId: UUID): List<InventoryTransactionEntity>
    fun findByReferenceId(referenceId: UUID): List<InventoryTransactionEntity>
}

