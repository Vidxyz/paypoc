package com.payments.platform.payments.client.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Response DTO for listing all seller accounts from ledger.
 */
data class LedgerSellersResponse(
    @JsonProperty("sellers")
    val sellers: List<LedgerSellerInfo> = emptyList(),
    
    @JsonProperty("error")
    val error: String? = null
)

/**
 * Seller account information from ledger.
 */
data class LedgerSellerInfo(
    @JsonProperty("sellerId")
    val sellerId: String,
    
    @JsonProperty("currency")
    val currency: String,
    
    @JsonProperty("accountId")
    val accountId: UUID,
    
    @JsonProperty("balanceCents")
    val balanceCents: Long
)

