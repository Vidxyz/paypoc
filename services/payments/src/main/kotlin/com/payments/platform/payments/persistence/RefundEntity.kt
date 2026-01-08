package com.payments.platform.payments.persistence

import com.payments.platform.payments.domain.Refund
import com.payments.platform.payments.domain.RefundState
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "refunds")
class RefundEntity(
    @Id
    @Column(name = "id")
    val id: UUID,
    
    @Column(name = "payment_id", nullable = false)
    val paymentId: UUID,
    
    @Column(name = "refund_amount_cents", nullable = false)
    val refundAmountCents: Long,
    
    @Column(name = "platform_fee_refund_cents", nullable = false)
    val platformFeeRefundCents: Long,
    
    @Column(name = "net_seller_refund_cents", nullable = false)
    val netSellerRefundCents: Long,
    
    @Column(name = "currency", nullable = false)
    val currency: String,
    
    @Column(name = "state", nullable = false)
    @Enumerated(EnumType.STRING)
    var state: RefundState,
    
    @Column(name = "stripe_refund_id", unique = true)
    var stripeRefundId: String?,
    
    @Column(name = "ledger_transaction_id")
    var ledgerTransactionId: UUID?,
    
    @Column(name = "idempotency_key", nullable = false, unique = true)
    val idempotencyKey: String,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant
) {
    companion object {
        fun fromDomain(refund: Refund): RefundEntity {
            return RefundEntity(
                id = refund.id,
                paymentId = refund.paymentId,
                refundAmountCents = refund.refundAmountCents,
                platformFeeRefundCents = refund.platformFeeRefundCents,
                netSellerRefundCents = refund.netSellerRefundCents,
                currency = refund.currency,
                state = refund.state,
                stripeRefundId = refund.stripeRefundId,
                ledgerTransactionId = refund.ledgerTransactionId,
                idempotencyKey = refund.idempotencyKey,
                createdAt = refund.createdAt,
                updatedAt = refund.updatedAt
            )
        }
    }
    
    fun toDomain(): Refund {
        return Refund(
            id = id,
            paymentId = paymentId,
            refundAmountCents = refundAmountCents,
            platformFeeRefundCents = platformFeeRefundCents,
            netSellerRefundCents = netSellerRefundCents,
            currency = currency,
            state = state,
            stripeRefundId = stripeRefundId,
            ledgerTransactionId = ledgerTransactionId,
            idempotencyKey = idempotencyKey,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}

