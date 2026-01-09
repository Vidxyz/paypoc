package com.payments.platform.payments.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for Chargeback entities.
 */
@Repository
interface ChargebackRepository : JpaRepository<ChargebackEntity, UUID> {
    /**
     * Finds a chargeback by Stripe Dispute ID.
     */
    fun findByStripeDisputeId(stripeDisputeId: String): ChargebackEntity?
    
    /**
     * Finds all chargebacks for a payment.
     */
    fun findByPaymentId(paymentId: UUID): List<ChargebackEntity>
    
    /**
     * Finds a chargeback by idempotency key.
     */
    fun findByIdempotencyKey(idempotencyKey: String): ChargebackEntity?
}

