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
    
    @Convert(converter = SellerRefundBreakdownConverter::class)
    @Column(name = "seller_refund_breakdown", columnDefinition = "JSONB")
    val sellerRefundBreakdown: List<com.payments.platform.payments.domain.SellerRefundBreakdown>?,
    
    @Convert(converter = OrderItemRefundSnapshotConverter::class)
    @Column(name = "order_items_refunded", columnDefinition = "JSONB")
    val orderItemsRefunded: List<com.payments.platform.payments.domain.OrderItemRefundSnapshot>?,
    
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
    // JPA requires no-arg constructor
    constructor() : this(
        id = UUID.randomUUID(),
        paymentId = UUID.randomUUID(),
        refundAmountCents = 0,
        platformFeeRefundCents = 0,
        netSellerRefundCents = 0,
        currency = "",
        sellerRefundBreakdown = null,
        orderItemsRefunded = null,
        state = RefundState.REFUNDING,
        stripeRefundId = null,
        ledgerTransactionId = null,
        idempotencyKey = "",
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
    
    companion object {
        fun fromDomain(refund: Refund): RefundEntity {
            return RefundEntity(
                id = refund.id,
                paymentId = refund.paymentId,
                refundAmountCents = refund.refundAmountCents,
                platformFeeRefundCents = refund.platformFeeRefundCents,
                netSellerRefundCents = refund.netSellerRefundCents,
                currency = refund.currency,
                sellerRefundBreakdown = refund.sellerRefundBreakdown,
                orderItemsRefunded = refund.orderItemsRefunded,
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
            sellerRefundBreakdown = sellerRefundBreakdown,
            orderItemsRefunded = orderItemsRefunded,
            state = state,
            stripeRefundId = stripeRefundId,
            ledgerTransactionId = ledgerTransactionId,
            idempotencyKey = idempotencyKey,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}

