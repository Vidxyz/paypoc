package com.payments.platform.inventory.domain

import java.util.UUID

data class Inventory(
    val id: UUID,
    val productId: UUID,
    val sellerId: String,
    val sku: String,
    val availableQuantity: Int,
    val reservedQuantity: Int,
    val allocatedQuantity: Int,
    val totalQuantity: Int,
    val lowStockThreshold: Int = 10
) {
    init {
        require(availableQuantity >= 0) { "availableQuantity must be non-negative" }
        require(reservedQuantity >= 0) { "reservedQuantity must be non-negative" }
        require(allocatedQuantity >= 0) { "allocatedQuantity must be non-negative" }
        require(totalQuantity >= 0) { "totalQuantity must be non-negative" }
        require(availableQuantity + reservedQuantity + allocatedQuantity <= totalQuantity) {
            "Sum of available, reserved, and allocated must not exceed total"
        }
    }
    
    fun canReserve(quantity: Int): Boolean = availableQuantity >= quantity
    
    fun canAllocate(quantity: Int): Boolean = reservedQuantity >= quantity
    
    /**
     * Soft reserve (add-to-cart): Move from available to reserved
     */
    fun softReserve(quantity: Int): Inventory {
        require(availableQuantity >= quantity) { "Insufficient stock available" }
        return copy(
            availableQuantity = availableQuantity - quantity,
            reservedQuantity = reservedQuantity + quantity
        )
    }
    
    /**
     * Hard lock (checkout): Move from reserved to allocated
     */
    fun hardAllocate(quantity: Int): Inventory {
        require(reservedQuantity >= quantity) { "Cannot allocate more than reserved" }
        return copy(
            reservedQuantity = reservedQuantity - quantity,
            allocatedQuantity = allocatedQuantity + quantity
        )
    }
    
    /**
     * Release soft reservation: Move from reserved back to available
     */
    fun releaseReservation(quantity: Int): Inventory {
        require(reservedQuantity >= quantity) { "Cannot release more than reserved" }
        return copy(
            availableQuantity = availableQuantity + quantity,
            reservedQuantity = reservedQuantity - quantity
        )
    }
    
    /**
     * Release hard allocation: Move from allocated back to available
     */
    fun releaseAllocation(quantity: Int): Inventory {
        require(allocatedQuantity >= quantity) { "Cannot release more than allocated" }
        return copy(
            availableQuantity = availableQuantity + quantity,
            allocatedQuantity = allocatedQuantity - quantity
        )
    }
    
    /**
     * Confirm sale: Move from allocated to sold (deduct from total)
     */
    fun confirmSale(quantity: Int): Inventory {
        require(allocatedQuantity >= quantity) { "Cannot sell more than allocated" }
        return copy(
            allocatedQuantity = allocatedQuantity - quantity,
            totalQuantity = totalQuantity - quantity
        )
    }
    
    /**
     * Adjust total stock (add or remove stock)
     */
    fun adjustStock(delta: Int): Inventory {
        if (delta > 0) {
            // Add stock to available
            return copy(
                availableQuantity = availableQuantity + delta,
                totalQuantity = totalQuantity + delta
            )
        } else {
            // Remove stock (from available first, then reserved, then allocated)
            val absDelta = -delta
            var remaining = absDelta
            var newAvailable = availableQuantity
            var newReserved = reservedQuantity
            var newAllocated = allocatedQuantity
            var newTotal = totalQuantity
            
            // Remove from available first
            val fromAvailable = minOf(newAvailable, remaining)
            newAvailable -= fromAvailable
            remaining -= fromAvailable
            
            // Then from reserved
            val fromReserved = minOf(newReserved, remaining)
            newReserved -= fromReserved
            remaining -= fromReserved
            
            // Then from allocated
            val fromAllocated = minOf(newAllocated, remaining)
            newAllocated -= fromAllocated
            remaining -= fromAllocated
            
            require(remaining == 0) { "Cannot decrease stock below current allocations" }
            
            return copy(
                availableQuantity = newAvailable,
                reservedQuantity = newReserved,
                allocatedQuantity = newAllocated,
                totalQuantity = newTotal - absDelta
            )
        }
    }
    
    fun isLowStock(): Boolean = availableQuantity <= lowStockThreshold
}

