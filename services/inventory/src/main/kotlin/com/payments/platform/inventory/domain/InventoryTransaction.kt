package com.payments.platform.inventory.domain

import java.time.Instant
import java.util.UUID

enum class TransactionType {
    STOCK_ADD,      // Stock added
    STOCK_REMOVE,   // Stock removed
    RESERVE,        // Soft reservation created
    ALLOCATE,       // Hard lock created (checkout)
    RELEASE,        // Reservation/allocation released
    SELL            // Stock sold (after payment)
}

data class InventoryTransaction(
    val id: UUID,
    val inventoryId: UUID,
    val transactionType: TransactionType,
    val quantity: Int,
    val referenceId: UUID?,  // cart_id, order_id, etc.
    val description: String?,
    val createdAt: Instant
)

