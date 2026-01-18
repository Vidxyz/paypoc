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
    
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    
    @Column(name = "buyer_id", nullable = false)
    val buyerId: String,
    
    @Column(name = "gross_amount_cents", nullable = false)
    val grossAmountCents: Long,
    
    @Column(name = "platform_fee_cents", nullable = false)
    val platformFeeCents: Long,
    
    @Column(name = "net_seller_amount_cents", nullable = false)
    val netSellerAmountCents: Long,
    
    @Column(name = "currency", nullable = false)
    val currency: String,
    
    @Convert(converter = SellerBreakdownConverter::class)
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    @Column(name = "seller_breakdown", nullable = false, columnDefinition = "JSONB")
    val sellerBreakdown: List<com.payments.platform.payments.domain.SellerBreakdown>,
    
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
        orderId = UUID.randomUUID(),
        buyerId = "",
        grossAmountCents = 0,
        platformFeeCents = 0,
        netSellerAmountCents = 0,
        currency = "",
        sellerBreakdown = emptyList(),
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
            orderId = orderId,
            buyerId = buyerId,
            grossAmountCents = grossAmountCents,
            platformFeeCents = platformFeeCents,
            netSellerAmountCents = netSellerAmountCents,
            currency = currency,
            sellerBreakdown = sellerBreakdown,
            state = state,
            stripePaymentIntentId = stripePaymentIntentId,
            ledgerTransactionId = ledgerTransactionId,
            idempotencyKey = idempotencyKey,
            createdAt = createdAt,
            updatedAt = updatedAt,
            refundedAt = refundedAt
        )
    }
    
    companion object {
        fun fromDomain(payment: Payment): PaymentEntity {
            return PaymentEntity(
                id = payment.id,
                orderId = payment.orderId,
                buyerId = payment.buyerId,
                grossAmountCents = payment.grossAmountCents,
                platformFeeCents = payment.platformFeeCents,
                netSellerAmountCents = payment.netSellerAmountCents,
                currency = payment.currency,
                sellerBreakdown = payment.sellerBreakdown,
                state = payment.state,
                stripePaymentIntentId = payment.stripePaymentIntentId,
                ledgerTransactionId = payment.ledgerTransactionId,
                idempotencyKey = payment.idempotencyKey,
                createdAt = payment.createdAt,
                updatedAt = payment.updatedAt,
                refundedAt = payment.refundedAt
            )
        }
    }
}
