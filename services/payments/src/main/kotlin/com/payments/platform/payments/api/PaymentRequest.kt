package com.payments.platform.payments.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.payments.platform.payments.domain.Payment
import com.payments.platform.payments.domain.PaymentState
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import java.util.UUID

/**
 * Request DTO for creating a payment.
 */
@Schema(description = "Request to create a new payment")
data class CreatePaymentRequestDto(
    @field:NotNull(message = "accountId is required")
    @JsonProperty("accountId")
    @Schema(description = "The customer account ID to debit from", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    val accountId: UUID,
    
    @field:NotNull(message = "amountCents is required")
    @field:Positive(message = "amountCents must be positive")
    @JsonProperty("amountCents")
    @Schema(description = "Payment amount in cents. Must be positive.", example = "1000", required = true)
    val amountCents: Long,
    
    @field:NotBlank(message = "currency is required")
    @field:Pattern(regexp = "^[A-Z]{3}$", message = "currency must be 3 uppercase letters")
    @JsonProperty("currency")
    @Schema(description = "ISO 4217 currency code (3 uppercase letters)", example = "USD", required = true)
    val currency: String,
    
    @JsonProperty("description")
    @Schema(description = "Optional description for the payment", example = "Payment for order #123", required = false)
    val description: String? = null
)

/**
 * Response DTO for payment operations.
 */
@Schema(description = "Payment response")
data class PaymentResponseDto(
    @Schema(description = "Unique payment ID", example = "660e8400-e29b-41d4-a716-446655440000")
    val id: UUID? = null,
    
    @Schema(description = "Payment amount in cents", example = "1000")
    val amountCents: Long? = null,
    
    @Schema(description = "ISO 4217 currency code", example = "USD")
    val currency: String? = null,
    
    @Schema(description = "Payment state (CREATED, CONFIRMING, AUTHORIZED, CAPTURED, FAILED)", example = "CREATED")
    val state: String? = null,
    
    @Schema(description = "Reference to the ledger transaction (ledger is source of truth)", example = "770e8400-e29b-41d4-a716-446655440000")
    val ledgerTransactionId: UUID? = null,
    
    @Schema(description = "Idempotency key for this payment", example = "payment_660e8400_1234567890")
    val idempotencyKey: String? = null,
    
    @Schema(description = "Payment creation timestamp (ISO 8601)", example = "2024-01-15T10:30:00Z")
    val createdAt: String? = null,
    
    @Schema(description = "Payment last update timestamp (ISO 8601)", example = "2024-01-15T10:30:00Z")
    val updatedAt: String? = null,
    
    @Schema(description = "Error message if the request failed", example = "Payment creation failed: Ledger rejected transaction. Insufficient funds...")
    val error: String? = null
) {
    companion object {
        fun fromDomain(payment: Payment): PaymentResponseDto {
            return PaymentResponseDto(
                id = payment.id,
                amountCents = payment.amountCents,
                currency = payment.currency,
                state = payment.state.name,
                ledgerTransactionId = payment.ledgerTransactionId,
                idempotencyKey = payment.idempotencyKey,
                createdAt = payment.createdAt.toString(),
                updatedAt = payment.updatedAt.toString()
            )
        }
    }
}

/**
 * Response DTO for balance query.
 */
@Schema(description = "Account balance response (delegated from Ledger Service)")
data class BalanceResponseDto(
    @Schema(description = "The account ID", example = "550e8400-e29b-41d4-a716-446655440000")
    val accountId: UUID? = null,
    
    @Schema(description = "ISO 4217 currency code", example = "USD")
    val currency: String? = null,
    
    @Schema(description = "Current balance in cents (from Ledger Service)", example = "125000")
    val balanceCents: Long? = null,
    
    @Schema(description = "Error message if the request failed", example = "Failed to get balance from ledger: Account not found")
    val error: String? = null
)

