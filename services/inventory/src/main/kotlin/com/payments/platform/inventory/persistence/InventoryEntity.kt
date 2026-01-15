package com.payments.platform.inventory.persistence

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "inventory",
    indexes = [
        Index(name = "idx_inventory_product_id", columnList = "product_id"),
        Index(name = "idx_inventory_seller_sku", columnList = "seller_id, sku", unique = true)
    ]
)
data class InventoryEntity(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),
    
    @Column(name = "product_id", nullable = false)
    val productId: UUID,
    
    @Column(name = "seller_id", nullable = false)
    val sellerId: String,
    
    @Column(name = "sku", nullable = false)
    val sku: String,
    
    @Column(name = "available_quantity", nullable = false)
    var availableQuantity: Int,
    
    @Column(name = "reserved_quantity", nullable = false)
    var reservedQuantity: Int,
    
    @Column(name = "allocated_quantity", nullable = false)
    var allocatedQuantity: Int,
    
    @Column(name = "total_quantity", nullable = false)
    var totalQuantity: Int,
    
    @Column(name = "low_stock_threshold", nullable = false)
    var lowStockThreshold: Int = 10,
    
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}

