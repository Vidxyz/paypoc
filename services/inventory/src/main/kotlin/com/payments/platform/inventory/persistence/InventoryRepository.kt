package com.payments.platform.inventory.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface InventoryRepository : JpaRepository<InventoryEntity, UUID> {
    
    fun findByProductId(productId: UUID): InventoryEntity?
    
    fun findBySellerIdAndSku(sellerId: String, sku: String): InventoryEntity?
    
    @Query("SELECT i FROM InventoryEntity i WHERE i.availableQuantity <= i.lowStockThreshold")
    fun findLowStockItems(): List<InventoryEntity>
}

