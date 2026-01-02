package com.payments.platform.payments.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PaymentRepository : JpaRepository<PaymentEntity, UUID> {
    fun findByIdempotencyKey(idempotencyKey: String): PaymentEntity?
}

