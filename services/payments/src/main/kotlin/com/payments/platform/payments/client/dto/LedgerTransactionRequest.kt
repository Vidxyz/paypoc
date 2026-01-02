package com.payments.platform.payments.client.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Request DTO for creating a ledger transaction.
 * 
 * This matches the Ledger Service API contract.
 */
data class LedgerTransactionRequest(
    @JsonProperty("accountId")
    val accountId: UUID,
    
    @JsonProperty("amountCents")
    val amountCents: Long,
    
    @JsonProperty("currency")
    val currency: String,
    
    @JsonProperty("idempotencyKey")
    val idempotencyKey: String,
    
    @JsonProperty("description")
    val description: String? = null
)

