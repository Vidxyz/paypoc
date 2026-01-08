package com.payments.platform.payments.kafka

import java.time.Instant
import java.util.UUID

/**
 * Event published when a payout is successfully completed by Stripe.
 * This event triggers the ledger write for the payout.
 */
data class PayoutCompletedEvent(
    override val eventId: UUID = UUID.randomUUID(),
    val payoutId: UUID,
    override val paymentId: UUID,  // Use payoutId as paymentId for Kafka key
    override val idempotencyKey: String,
    val sellerId: String,
    val amountCents: Long,
    val currency: String,
    val stripeTransferId: String,
    override val attempt: Int = 1,
    override val createdAt: Instant = Instant.now(),
    override val payload: Map<String, Any> = emptyMap()
) : PaymentMessage(
    eventId = eventId,
    paymentId = paymentId,
    idempotencyKey = idempotencyKey,
    type = "PAYOUT_COMPLETED",
    attempt = attempt,
    createdAt = createdAt,
    payload = payload
)

