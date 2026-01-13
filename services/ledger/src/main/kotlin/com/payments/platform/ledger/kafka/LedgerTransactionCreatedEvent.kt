package com.payments.platform.ledger.kafka

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * LedgerTransactionCreatedEvent - published by Ledger Service after creating a transaction.
 * 
 * This event notifies the payments service that a ledger transaction was created,
 * allowing it to update the payment/refund/payout/chargeback record with the ledger_transaction_id.
 * 
 * For payments: paymentId is set, refundId and chargebackId are null
 * For refunds: paymentId and refundId are both set, chargebackId is null
 * For chargebacks: chargebackId is set, paymentId and refundId are null (paymentId may also be set for reference)
 * For payouts: paymentId is null, refundId is null, chargebackId is null (payoutId could be added in future)
 */
data class LedgerTransactionCreatedEvent(
    @JsonProperty("eventId")
    val eventId: UUID = UUID.randomUUID(),
    
    @JsonProperty("paymentId")
    val paymentId: UUID? = null,
    
    @JsonProperty("refundId")
    val refundId: UUID? = null,
    
    @JsonProperty("chargebackId")
    val chargebackId: UUID? = null,
    
    @JsonProperty("ledgerTransactionId")
    val ledgerTransactionId: UUID,
    
    @JsonProperty("idempotencyKey")
    val idempotencyKey: String,
    
    @JsonProperty("createdAt")
    val createdAt: Instant = Instant.now()
)

