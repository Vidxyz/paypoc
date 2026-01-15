package com.payments.platform.inventory.persistence

import com.payments.platform.inventory.domain.ReservationStatus
import com.payments.platform.inventory.domain.ReservationType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ReservationRepository : JpaRepository<ReservationEntity, UUID> {
    
    fun findByCartId(cartId: UUID): List<ReservationEntity>
    
    fun findByInventoryIdAndStatus(inventoryId: UUID, status: ReservationStatus): List<ReservationEntity>
    
    @Query("SELECT r FROM ReservationEntity r WHERE r.status = :status AND r.expiresAt < :now")
    fun findExpiredReservations(
        @Param("status") status: ReservationStatus,
        @Param("now") now: Instant
    ): List<ReservationEntity>
    
    fun findByCartIdAndReservationType(cartId: UUID, reservationType: ReservationType): List<ReservationEntity>
}
