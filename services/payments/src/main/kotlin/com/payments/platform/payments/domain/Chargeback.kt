package com.payments.platform.payments.domain

import java.time.Instant
import java.util.UUID

/**
 * Chargeback domain entity.
 * 
 * Represents a chargeback (dispute) initiated by the buyer's bank.
 * Chargebacks are separate from refunds - they are initiated by the bank,
 * not the merchant, and have a complex lifecycle with evidence submission.
 * 
 * Key differences from refunds:
 * - Initiated by buyer's bank (not merchant)
 * - Money is debited immediately when dispute is created
 * - Platform can contest by submitting evidence
 * - Includes dispute fee ($15-25) charged by Stripe
 * - Can happen weeks/months after payment
 */
data class Chargeback(
    val id: UUID,
    val paymentId: UUID,
    val chargebackAmountCents: Long,  // Amount disputed (can be partial)
    val disputeFeeCents: Long,        // Stripe dispute fee ($15-25, never refunded)
    val currency: String,
    val state: ChargebackState,
    val stripeDisputeId: String,      // Stripe Dispute ID (unique)
    val stripeChargeId: String,       // Stripe Charge ID
    val reason: String?,              // Dispute reason (fraud, product_unacceptable, etc.)
    val evidenceDueBy: Instant?,      // Deadline to submit evidence
    val ledgerTransactionId: UUID?,    // NULL until dispute created (money debited)
    val idempotencyKey: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val closedAt: Instant?,
    val outcome: ChargebackOutcome?    // WON, LOST, WITHDRAWN (set when closed)
)

