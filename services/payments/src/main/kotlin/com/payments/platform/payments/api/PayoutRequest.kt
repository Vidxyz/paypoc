package com.payments.platform.payments.api

import com.payments.platform.payments.domain.Payout
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * Request DTO for creating a payout.
 */
@Schema(description = "Request to create a payout")
data class CreatePayoutRequestDto(
    @Schema(description = "Seller ID", example = "seller_123", required = true)
    val sellerId: String,
    
    @Schema(description = "Amount to payout in cents", example = "9000", required = true)
    val amountCents: Long,
    
    @Schema(description = "ISO 4217 currency code", example = "CAD", required = true)
    val currency: String,
    
    @Schema(description = "Optional description for the payout", example = "Payout for orders #123, #124", required = false)
    val description: String? = null
)

/**
 * Response DTO for payout operations.
 */
@Schema(description = "Payout response")
data class PayoutResponseDto(
    @Schema(description = "Unique payout ID", example = "660e8400-e29b-41d4-a716-446655440000")
    val id: UUID? = null,
    
    @Schema(description = "Seller ID", example = "seller_123")
    val sellerId: String? = null,
    
    @Schema(description = "Amount in cents", example = "9000")
    val amountCents: Long? = null,
    
    @Schema(description = "ISO 4217 currency code", example = "CAD")
    val currency: String? = null,
    
    @Schema(description = "Payout state (PENDING, PROCESSING, COMPLETED, FAILED)", example = "PROCESSING")
    val state: String? = null,
    
    @Schema(description = "Stripe Transfer ID", example = "tr_1234567890")
    val stripeTransferId: String? = null,
    
    @Schema(description = "Reference to the ledger transaction for the payout", example = "770e8400-e29b-41d4-a716-446655440000")
    val ledgerTransactionId: UUID? = null,
    
    @Schema(description = "Idempotency key for this payout", example = "payout_660e8400_1234567890")
    val idempotencyKey: String? = null,
    
    @Schema(description = "Optional description", example = "Payout for pending funds")
    val description: String? = null,
    
    @Schema(description = "Payout creation timestamp (ISO 8601)", example = "2024-01-15T10:30:00Z")
    val createdAt: String? = null,
    
    @Schema(description = "Payout last update timestamp (ISO 8601)", example = "2024-01-15T10:30:00Z")
    val updatedAt: String? = null,
    
    @Schema(description = "Payout completion timestamp (ISO 8601)", example = "2024-01-15T10:35:00Z")
    val completedAt: String? = null,
    
    @Schema(description = "Failure reason if payout failed", example = "Insufficient funds")
    val failureReason: String? = null,
    
    @Schema(description = "Error message if the request failed", example = "Payout creation failed: Invalid request")
    val error: String? = null
) {
    companion object {
        fun fromDomain(payout: Payout): PayoutResponseDto {
            return PayoutResponseDto(
                id = payout.id,
                sellerId = payout.sellerId,
                amountCents = payout.amountCents,
                currency = payout.currency,
                state = payout.state.name,
                stripeTransferId = payout.stripeTransferId,
                ledgerTransactionId = payout.ledgerTransactionId,
                idempotencyKey = payout.idempotencyKey,
                description = payout.description,
                createdAt = payout.createdAt.toString(),
                updatedAt = payout.updatedAt.toString(),
                completedAt = payout.completedAt?.toString(),
                failureReason = payout.failureReason
            )
        }
    }
}

/**
 * Response DTO for listing payouts.
 */
@Schema(description = "List of payouts response")
data class ListPayoutsResponseDto(
    @Schema(description = "List of payouts")
    val payouts: List<PayoutResponseDto>? = null,
    
    @Schema(description = "Current page number (0-indexed)", example = "0")
    val page: Int = 0,
    
    @Schema(description = "Page size", example = "20")
    val size: Int = 0,
    
    @Schema(description = "Total number of payouts", example = "45")
    val total: Long = 0,
    
    @Schema(description = "Total number of pages", example = "3")
    val totalPages: Int = 0,
    
    @Schema(description = "Error message if the request failed", example = "Failed to retrieve payouts: Seller not found")
    val error: String? = null
)

