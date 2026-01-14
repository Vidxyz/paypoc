package com.payments.platform.inventory.domain

import java.time.Instant
import java.util.UUID

enum class ReservationType {
    SOFT,  // Add-to-cart (15min TTL, can be overridden if stock available)
    HARD   // Checkout (until payment completes/fails)
}

enum class ReservationStatus {
    ACTIVE,    // Reservation is active
    EXPIRED,   // Reservation expired
    RELEASED,  // Reservation was released
    ALLOCATED, // Reservation converted to hard allocation
    SOLD       // Stock was sold (after payment confirmation)
}

data class Reservation(
    val id: UUID,
    val inventoryId: UUID,
    val cartId: UUID,
    val quantity: Int,
    val reservationType: ReservationType,
    val status: ReservationStatus,
    val expiresAt: Instant,
    val createdAt: Instant
)
