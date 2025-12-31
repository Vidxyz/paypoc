package com.payments.platform.ledger.api

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.util.UUID

@Schema(description = "Request to create a new ledger transaction")
data class CreateTransactionRequestDto(
    @field:NotNull(message = "accountId is required")
    @JsonProperty("accountId")
    @Schema(description = "The account ID for this transaction", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    val accountId: UUID,

    @field:NotNull(message = "amountCents is required")
    @JsonProperty("amountCents")
    @Schema(description = "Amount in cents. Positive for credits, negative for debits. Must be non-zero.", example = "-2500", required = true)
    val amountCents: Long,

    @field:NotBlank(message = "currency is required")
    @field:Pattern(regexp = "^[A-Z]{3}$", message = "currency must be 3 uppercase letters")
    @JsonProperty("currency")
    @Schema(description = "ISO 4217 currency code (3 uppercase letters)", example = "USD", required = true)
    val currency: String,

    @field:NotBlank(message = "idempotencyKey is required")
    @JsonProperty("idempotencyKey")
    @Schema(description = "Unique key to prevent duplicate processing. If a transaction with this key already exists, the existing transaction is returned.", example = "refund_abc_123", required = true)
    val idempotencyKey: String,

    @JsonProperty("description")
    @Schema(description = "Optional description for the transaction", example = "Refund for order #123", required = false)
    val description: String? = null
)

@Schema(description = "Account balance response")
data class BalanceResponseDto(
    @JsonProperty("accountId")
    @Schema(description = "The account ID", example = "550e8400-e29b-41d4-a716-446655440000")
    val accountId: UUID? = null,

    @JsonProperty("currency")
    @Schema(description = "ISO 4217 currency code", example = "USD")
    val currency: String? = null,

    @JsonProperty("balanceCents")
    @Schema(description = "Current balance in cents (sum of all transactions)", example = "125000")
    val balanceCents: Long? = null,

    @JsonProperty("error")
    @Schema(description = "Error message if the request failed", example = "Account not found")
    val error: String? = null
)

