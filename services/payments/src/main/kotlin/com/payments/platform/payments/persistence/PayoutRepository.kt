package com.payments.platform.payments.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PayoutRepository : JpaRepository<PayoutEntity, UUID> {
    fun findBySellerId(sellerId: String): List<PayoutEntity>
    fun findByStripeTransferId(stripeTransferId: String): PayoutEntity?
    fun findByIdempotencyKey(idempotencyKey: String): PayoutEntity?
    fun findByState(state: com.payments.platform.payments.domain.PayoutState): List<PayoutEntity>
    
    @Query("SELECT p FROM PayoutEntity p WHERE p.sellerId = :sellerId AND p.state IN :states")
    fun findBySellerIdAndStateIn(
        @Param("sellerId") sellerId: String,
        @Param("states") states: List<com.payments.platform.payments.domain.PayoutState>
    ): List<PayoutEntity>
}

