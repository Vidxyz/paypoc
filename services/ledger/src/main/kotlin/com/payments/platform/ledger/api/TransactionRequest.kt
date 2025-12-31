package com.payments.platform.ledger.api

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.util.UUID

data class CreateTransactionRequestDto(
    @field:NotNull(message = "accountId is required")
    @JsonProperty("accountId")
    val accountId: UUID,

    @field:NotNull(message = "amountCents is required")
    @JsonProperty("amountCents")
    val amountCents: Long,

    @field:NotBlank(message = "currency is required")
    @field:Pattern(regexp = "^[A-Z]{3}$", message = "currency must be 3 uppercase letters")
    @JsonProperty("currency")
    val currency: String,

    @field:NotBlank(message = "idempotencyKey is required")
    @JsonProperty("idempotencyKey")
    val idempotencyKey: String,

    @JsonProperty("description")
    val description: String? = null
)

data class BalanceResponseDto(
    @JsonProperty("accountId")
    val accountId: UUID? = null,

    @JsonProperty("currency")
    val currency: String? = null,

    @JsonProperty("balanceCents")
    val balanceCents: Long? = null,

    @JsonProperty("error")
    val error: String? = null
)

