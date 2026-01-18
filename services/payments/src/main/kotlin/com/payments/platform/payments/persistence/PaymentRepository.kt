package com.payments.platform.payments.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PaymentRepository : JpaRepository<PaymentEntity, UUID> {
    fun findByIdempotencyKey(idempotencyKey: String): PaymentEntity?
    fun findByStripePaymentIntentId(stripePaymentIntentId: String): PaymentEntity?
    fun findByOrderId(orderId: UUID): PaymentEntity?
    fun findByBuyerId(buyerId: String, pageable: Pageable): Page<PaymentEntity>
    
    /**
     * Finds payments where the given sellerId appears in the sellerBreakdown JSONB array.
     * Uses PostgreSQL JSONB operators to search within the seller_breakdown array.
     * 
     * This uses a native SQL query because JSONB operations are PostgreSQL-specific
     * and not supported in standard JPQL.
     */
    @Query(
        value = """
        SELECT p.* FROM payments p 
        WHERE EXISTS (
            SELECT 1 FROM jsonb_array_elements(p.seller_breakdown) AS breakdown 
            WHERE breakdown->>'sellerId' = :sellerId
        )
    """,
        countQuery = """
        SELECT COUNT(p.*) FROM payments p 
        WHERE EXISTS (
            SELECT 1 FROM jsonb_array_elements(p.seller_breakdown) AS breakdown 
            WHERE breakdown->>'sellerId' = :sellerId
        )
    """,
        nativeQuery = true
    )
    fun findBySellerId(@Param("sellerId") sellerId: String, pageable: Pageable): Page<PaymentEntity>
}

