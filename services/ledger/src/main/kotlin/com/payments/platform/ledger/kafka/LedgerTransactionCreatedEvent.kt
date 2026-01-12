package com.payments.platform.ledger.kafka

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * LedgerTransactionCreatedEvent - published by Ledger Service after creating a transaction.
 * 
 * This event notifies the payments service that a ledger transaction was created,
 * allowing it to update the payment record with the ledger_transaction_id.
 */
data class LedgerTransactionCreatedEvent(
    @JsonProperty("eventId")
    val eventId: UUID = UUID.randomUUID(),
    
    @JsonProperty("paymentId")
    val paymentId: UUID,
    
    @JsonProperty("ledgerTransactionId")
    val ledgerTransactionId: UUID,
    
    @JsonProperty("idempotencyKey")
    val idempotencyKey: String,
    
    @JsonProperty("createdAt")
    val createdAt: Instant = Instant.now()
)

