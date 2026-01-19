package com.payments.platform.payments.domain

import java.time.Instant
import java.util.UUID

/**
 * Payment domain entity.
 * 
 * Important: This stores workflow state ONLY.
 * - gross_amount_cents, platform_fee_cents, net_seller_amount_cents are metadata, not balance
 * - ledger_transaction_id is a reference (NULL until capture), not truth
 * - state is workflow-only
 * 
 * If this database is deleted, money is still correct in the ledger.
 * 
 * One payment per order - can have multiple sellers via sellerBreakdown.
 */
data class Payment(
    val id: UUID,
    val orderId: UUID,
    val buyerId: String,
    val grossAmountCents: Long,
    val platformFeeCents: Long,  // Total platform fee (sum of all seller platform fees)
    val netSellerAmountCents: Long,  // Total net seller amounts (sum of all seller net amounts)
    val currency: String,
    val sellerBreakdown: List<SellerBreakdown>,  // Per-seller breakdown
    val state: PaymentState,
    val stripePaymentIntentId: String?,
    val ledgerTransactionId: UUID?,  // NULL until capture
    val idempotencyKey: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val refundedAt: Instant? = null  // NULL until refund completes
)
