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
 */
data class Refund(
    val id: UUID,
    val paymentId: UUID,
    val refundAmountCents: Long,
    val platformFeeRefundCents: Long,
    val netSellerRefundCents: Long,
    val currency: String,
    val state: RefundState,
    val stripeRefundId: String?,
    val ledgerTransactionId: UUID?,  // NULL until refund confirmed
    val idempotencyKey: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

