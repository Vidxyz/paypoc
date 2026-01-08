package com.payments.platform.payments.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface RefundRepository : JpaRepository<RefundEntity, UUID> {
    fun findByPaymentId(paymentId: UUID): List<RefundEntity>
    fun findByStripeRefundId(stripeRefundId: String): RefundEntity?
    fun findByIdempotencyKey(idempotencyKey: String): RefundEntity?
}

