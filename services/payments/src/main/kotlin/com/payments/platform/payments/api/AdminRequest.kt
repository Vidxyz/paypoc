package com.payments.platform.payments.api

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * Response DTO for listing sellers with balances.
 */
@Schema(description = "Response containing list of sellers with their balances")
data class ListSellersResponseDto(
    @JsonProperty("sellers")
    @Schema(description = "List of sellers with their balances")
    val sellers: List<SellerWithBalanceDto> = emptyList(),
    
    @JsonProperty("error")
    @Schema(description = "Error message if request failed")
    val error: String? = null
)

/**
 * DTO for a seller with balance information.
 */
@Schema(description = "Seller with balance information")
data class SellerWithBalanceDto(
    @JsonProperty("sellerId")
    @Schema(description = "Seller ID", example = "seller_123")
    val sellerId: String,
    
    @JsonProperty("currency")
    @Schema(description = "Currency code", example = "USD")
    val currency: String,
    
    @JsonProperty("accountId")
    @Schema(description = "Ledger account ID for this seller")
    val accountId: UUID,
    
    @JsonProperty("balanceCents")
    @Schema(description = "Current balance in cents", example = "50000")
    val balanceCents: Long
)

