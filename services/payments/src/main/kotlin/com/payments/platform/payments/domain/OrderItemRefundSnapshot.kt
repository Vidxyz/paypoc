package com.payments.platform.payments.domain

import java.util.UUID

/**
 * Snapshot of an order item that was refunded.
 * 
 * This is stored in the refund entity to track which specific order items
 * were refunded, enabling the order service to update its state.
 */
data class OrderItemRefundSnapshot(
    val orderItemId: UUID,
    val quantity: Int,
    val sellerId: String,
    val priceCents: Long
) {
    init {
        require(quantity > 0) { "Refund quantity must be positive" }
        require(priceCents > 0) { "Price must be positive" }
        require(sellerId.isNotBlank()) { "Seller ID cannot be blank" }
    }
}
