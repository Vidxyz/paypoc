package com.payments.platform.payments.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PaymentRepository : JpaRepository<PaymentEntity, UUID> {
    fun findByIdempotencyKey(idempotencyKey: String): PaymentEntity?
    fun findByStripePaymentIntentId(stripePaymentIntentId: String): PaymentEntity?
    fun findByBuyerId(buyerId: String, pageable: Pageable): Page<PaymentEntity>
    fun findBySellerId(sellerId: String, pageable: Pageable): Page<PaymentEntity>
}

