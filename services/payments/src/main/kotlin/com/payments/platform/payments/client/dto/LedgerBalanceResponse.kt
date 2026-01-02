package com.payments.platform.payments.client.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Response DTO for balance query from ledger.
 * 
 * This matches the Ledger Service API contract.
 */
data class LedgerBalanceResponse(
    @JsonProperty("accountId")
    val accountId: UUID,
    
    @JsonProperty("currency")
    val currency: String,
    
    @JsonProperty("balanceCents")
    val balanceCents: Long
)

