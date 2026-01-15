package com.payments.platform.inventory.persistence

import com.payments.platform.inventory.domain.TransactionType
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "inventory_transactions",
    indexes = [
        Index(name = "idx_transactions_inventory_id", columnList = "inventory_id"),
        Index(name = "idx_transactions_reference_id", columnList = "reference_id"),
        Index(name = "idx_transactions_type", columnList = "transaction_type"),
        Index(name = "idx_transactions_created_at", columnList = "created_at")
    ]
)
data class InventoryTransactionEntity(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),
    
    @Column(name = "inventory_id", nullable = false)
    val inventoryId: UUID,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    val transactionType: TransactionType,
    
    @Column(name = "quantity", nullable = false)
    val quantity: Int,
    
    @Column(name = "reference_id")
    val referenceId: UUID?,
    
    @Column(name = "description", columnDefinition = "TEXT")
    val description: String?,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)

