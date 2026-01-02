package com.payments.platform.payments.client.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Response DTO from ledger transaction creation.
 * 
 * This matches the Ledger Service API contract.
 */
data class LedgerTransactionResponse(
    @JsonProperty("transactionId")
    val transactionId: UUID,
    
    @JsonProperty("accountId")
    val accountId: UUID,
    
    @JsonProperty("amountCents")
    val amountCents: Long,
    
    @JsonProperty("currency")
    val currency: String,
    
    @JsonProperty("idempotencyKey")
    val idempotencyKey: String,
    
    @JsonProperty("description")
    val description: String?,
    
    @JsonProperty("createdAt")
    val createdAt: String  // ISO 8601 string from ledger
)

