package com.payments.platform.payments.domain

import java.time.Instant
import java.util.UUID

/**
 * Refund domain entity.
 * 
 * Important: This stores workflow state ONLY.
 * - refund_amount_cents, platform_fee_refund_cents, net_seller_refund_cents are metadata, not balance
 * - ledger_transaction_id is a reference (NULL until refund confirmed), not truth
 * - state is workflow-only
 * 
 * If this database is deleted, money is still correct in the ledger.
 * 
 * For partial refunds with multiple sellers, sellerRefundBreakdown stores the per-seller breakdown.
 * orderItemsRefunded stores which specific order items were refunded (for order service tracking).
 */
data class Refund(
    val id: UUID,
    val paymentId: UUID,
    val refundAmountCents: Long,
    val platformFeeRefundCents: Long,
    val netSellerRefundCents: Long,
    val currency: String,
    val sellerRefundBreakdown: List<SellerRefundBreakdown>?,  // Per-seller refund breakdown (for partial refunds)
    val orderItemsRefunded: List<OrderItemRefundSnapshot>?,  // Which order items were refunded (for order service tracking)
    val state: RefundState,
    val stripeRefundId: String?,
    val ledgerTransactionId: UUID?,  // NULL until refund confirmed
    val idempotencyKey: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

