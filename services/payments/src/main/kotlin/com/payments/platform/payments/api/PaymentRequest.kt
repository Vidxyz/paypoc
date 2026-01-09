package com.payments.platform.payments.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.payments.platform.payments.domain.Payment
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
    @field:NotBlank(message = "buyerId is required")
    @JsonProperty("buyerId")
    @Schema(description = "The buyer ID", example = "buyer_123", required = true)
    val buyerId: String,
    
    @field:NotBlank(message = "sellerId is required")
    @JsonProperty("sellerId")
    @Schema(description = "The seller ID", example = "seller_456", required = true)
    val sellerId: String,
    
    @field:NotNull(message = "grossAmountCents is required")
    @field:Positive(message = "grossAmountCents must be positive")
    @JsonProperty("grossAmountCents")
    @Schema(description = "Total payment amount in cents (before platform fee). Must be positive.", example = "10000", required = true)
    val grossAmountCents: Long,
    
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
    
    @Schema(description = "Buyer ID", example = "buyer_123")
    val buyerId: String? = null,
    
    @Schema(description = "Seller ID", example = "seller_456")
    val sellerId: String? = null,
    
    @Schema(description = "Total payment amount in cents (gross)", example = "10000")
    val grossAmountCents: Long? = null,
    
    @Schema(description = "Platform fee in cents (10% of gross)", example = "1000")
    val platformFeeCents: Long? = null,
    
    @Schema(description = "Net seller amount in cents (90% of gross)", example = "9000")
    val netSellerAmountCents: Long? = null,
    
    @Schema(description = "ISO 4217 currency code", example = "USD")
    val currency: String? = null,
    
    @Schema(description = "Payment state (CREATED, CONFIRMING, AUTHORIZED, CAPTURED, FAILED)", example = "CREATED")
    val state: String? = null,
    
    @Schema(description = "Stripe PaymentIntent ID", example = "pi_1234567890")
    val stripePaymentIntentId: String? = null,
    
    @Schema(description = "Stripe client secret for confirming payment on frontend", example = "pi_1234567890_secret_abc123")
    val clientSecret: String? = null,
    
    @Schema(description = "Reference to the ledger transaction (NULL until capture, ledger is source of truth)", example = "770e8400-e29b-41d4-a716-446655440000")
    val ledgerTransactionId: UUID? = null,
    
    @Schema(description = "Idempotency key for this payment", example = "payment_660e8400_1234567890")
    val idempotencyKey: String? = null,
    
    @Schema(description = "Payment creation timestamp (ISO 8601)", example = "2024-01-15T10:30:00Z")
    val createdAt: String? = null,
    
    @Schema(description = "Payment last update timestamp (ISO 8601)", example = "2024-01-15T10:30:00Z")
    val updatedAt: String? = null,
    
    @Schema(description = "Whether this payment has any chargebacks", example = "false")
    val hasChargeback: Boolean = false,
    
    @Schema(description = "Latest chargeback state (if hasChargeback is true)", example = "NEEDS_RESPONSE", required = false)
    val chargebackState: String? = null,
    
    @Schema(description = "Latest chargeback amount in cents (if hasChargeback is true)", example = "10000", required = false)
    val chargebackAmountCents: Long? = null,
    
    @Schema(description = "Latest chargeback ID (if hasChargeback is true)", example = "880e8400-e29b-41d4-a716-446655440000", required = false)
    val latestChargebackId: UUID? = null,
    
    @Schema(description = "Error message if the request failed", example = "Payment creation failed: Invalid request")
    val error: String? = null
) {
    companion object {
        fun fromDomain(
            payment: Payment, 
            clientSecret: String? = null,
            chargebackInfo: ChargebackInfo? = null
        ): PaymentResponseDto {
            return PaymentResponseDto(
                id = payment.id,
                buyerId = payment.buyerId,
                sellerId = payment.sellerId,
                grossAmountCents = payment.grossAmountCents,
                platformFeeCents = payment.platformFeeCents,
                netSellerAmountCents = payment.netSellerAmountCents,
                currency = payment.currency,
                state = payment.state.name,
                stripePaymentIntentId = payment.stripePaymentIntentId,
                clientSecret = clientSecret,
                ledgerTransactionId = payment.ledgerTransactionId,
                idempotencyKey = payment.idempotencyKey,
                createdAt = payment.createdAt.toString(),
                updatedAt = payment.updatedAt.toString(),
                hasChargeback = chargebackInfo?.hasChargeback ?: false,
                chargebackState = chargebackInfo?.state,
                chargebackAmountCents = chargebackInfo?.amountCents,
                latestChargebackId = chargebackInfo?.chargebackId
            )
        }
    }
}

/**
 * Chargeback summary information for a payment.
 */
data class ChargebackInfo(
    val hasChargeback: Boolean,
    val chargebackId: UUID? = null,
    val state: String? = null,
    val amountCents: Long? = null
)

/**
 * Response DTO for listing payments.
 */
@Schema(description = "List of payments response")
data class ListPaymentsResponseDto(
    @Schema(description = "List of payments")
    val payments: List<PaymentResponseDto> = emptyList(),
    
    @Schema(description = "Current page number (0-indexed)", example = "0")
    val page: Int = 0,
    
    @Schema(description = "Page size", example = "50")
    val size: Int = 0,
    
    @Schema(description = "Total number of payments in this page", example = "10")
    val total: Int = 0,
    
    @Schema(description = "Error message if the request failed", example = "Unauthorized: buyerId not found in request")
    val error: String? = null
)

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
