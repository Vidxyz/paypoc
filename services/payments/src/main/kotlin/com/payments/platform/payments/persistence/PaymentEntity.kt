package com.payments.platform.payments.persistence

import com.payments.platform.payments.domain.Payment
import com.payments.platform.payments.domain.PaymentState
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for Payment.
 * Maps to the payments table in the database.
 */
@Entity
@Table(name = "payments")
class PaymentEntity(
    @Id
    @Column(name = "id")
    val id: UUID,
    
    @Column(name = "buyer_id", nullable = false)
    val buyerId: String,
    
    @Column(name = "seller_id", nullable = false)
    val sellerId: String,
    
    @Column(name = "gross_amount_cents", nullable = false)
    val grossAmountCents: Long,
    
    @Column(name = "platform_fee_cents", nullable = false)
    val platformFeeCents: Long,
    
    @Column(name = "net_seller_amount_cents", nullable = false)
    val netSellerAmountCents: Long,
    
    @Column(name = "currency", nullable = false)
    val currency: String,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    val state: PaymentState,
    
    @Column(name = "stripe_payment_intent_id")
    val stripePaymentIntentId: String?,
    
    @Column(name = "ledger_transaction_id")
    val ledgerTransactionId: UUID?,  // NULL until capture
    
    @Column(name = "idempotency_key", nullable = false, unique = true)
    val idempotencyKey: String,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
    
    @Column(name = "refunded_at")
    val refundedAt: Instant?
) {
    // JPA requires no-arg constructor
    constructor() : this(
        id = UUID.randomUUID(),
        buyerId = "",
        sellerId = "",
        grossAmountCents = 0,
        platformFeeCents = 0,
        netSellerAmountCents = 0,
        currency = "",
        state = PaymentState.CREATED,
        stripePaymentIntentId = null,
        ledgerTransactionId = null,
        idempotencyKey = "",
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        refundedAt = null
    )
    
    fun toDomain(): Payment {
        return Payment(
            id = id,
            buyerId = buyerId,
            sellerId = sellerId,
            grossAmountCents = grossAmountCents,
            platformFeeCents = platformFeeCents,
            netSellerAmountCents = netSellerAmountCents,
            currency = currency,
            state = state,
            stripePaymentIntentId = stripePaymentIntentId,
            ledgerTransactionId = ledgerTransactionId,
            idempotencyKey = idempotencyKey,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
    
    companion object {
        fun fromDomain(payment: Payment): PaymentEntity {
            return PaymentEntity(
                id = payment.id,
                buyerId = payment.buyerId,
                sellerId = payment.sellerId,
                grossAmountCents = payment.grossAmountCents,
                platformFeeCents = payment.platformFeeCents,
                netSellerAmountCents = payment.netSellerAmountCents,
                currency = payment.currency,
                state = payment.state,
                stripePaymentIntentId = payment.stripePaymentIntentId,
                ledgerTransactionId = payment.ledgerTransactionId,
                idempotencyKey = payment.idempotencyKey,
                createdAt = payment.createdAt,
                updatedAt = payment.updatedAt,
                refundedAt = null  // Set via separate update when refund completes
            )
        }
    }
}
