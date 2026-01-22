package com.payments.platform.payments.persistence

import com.payments.platform.payments.domain.PayoutState
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PayoutRepository : JpaRepository<PayoutEntity, UUID> {
    fun findBySellerId(sellerId: String): List<PayoutEntity>
    fun findBySellerId(sellerId: String, pageable: Pageable): Page<PayoutEntity>
    fun findByStripeTransferId(stripeTransferId: String): PayoutEntity?
    fun findByIdempotencyKey(idempotencyKey: String): PayoutEntity?
    fun findByState(state: PayoutState): List<PayoutEntity>
    
    @Query("SELECT p FROM PayoutEntity p WHERE p.sellerId = :sellerId AND p.state IN :states")
    fun findBySellerIdAndStateIn(
        @Param("sellerId") sellerId: String,
        @Param("states") states: List<PayoutState>
    ): List<PayoutEntity>
}

