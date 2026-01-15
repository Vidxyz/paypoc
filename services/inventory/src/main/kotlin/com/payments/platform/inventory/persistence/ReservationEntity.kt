package com.payments.platform.inventory.persistence

import com.payments.platform.inventory.domain.ReservationStatus
import com.payments.platform.inventory.domain.ReservationType
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "inventory_reservations",
    indexes = [
        Index(name = "idx_reservations_inventory_id", columnList = "inventory_id"),
        Index(name = "idx_reservations_cart_id", columnList = "cart_id"),
        Index(name = "idx_reservations_expires_at", columnList = "expires_at"),
        Index(name = "idx_reservations_type", columnList = "reservation_type")
    ]
)
data class ReservationEntity(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),
    
    @Column(name = "inventory_id", nullable = false)
    val inventoryId: UUID,
    
    @Column(name = "cart_id", nullable = false)
    val cartId: UUID,
    
    @Column(name = "quantity", nullable = false)
    val quantity: Int,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_type", nullable = false)
    val reservationType: ReservationType,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ReservationStatus,
    
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)
