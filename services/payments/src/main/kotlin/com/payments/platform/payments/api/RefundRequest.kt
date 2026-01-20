package com.payments.platform.payments.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.payments.platform.payments.domain.Refund
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * Response DTO for refund operations.
 */
@Schema(description = "Refund response")
data class RefundResponseDto(
    @Schema(description = "Unique refund ID", example = "770e8400-e29b-41d4-a716-446655440000")
    val id: UUID? = null,
    
    @Schema(description = "Payment ID that was refunded", example = "660e8400-e29b-41d4-a716-446655440000")
    val paymentId: UUID? = null,
    
    @Schema(description = "Total refund amount in cents", example = "10000")
    val refundAmountCents: Long? = null,
    
    @Schema(description = "Platform fee refund in cents (10% of refund amount)", example = "1000")
    val platformFeeRefundCents: Long? = null,
    
    @Schema(description = "Net seller refund in cents (90% of refund amount)", example = "9000")
    val netSellerRefundCents: Long? = null,
    
    @Schema(description = "ISO 4217 currency code", example = "CAD")
    val currency: String? = null,
    
    @Schema(description = "Refund state (REFUNDING, REFUNDED, FAILED)", example = "REFUNDING")
    val state: String? = null,
    
    @Schema(description = "Stripe Refund ID", example = "re_1234567890")
    val stripeRefundId: String? = null,
    
    @Schema(description = "Reference to the ledger transaction (NULL until refund confirmed)", example = "880e8400-e29b-41d4-a716-446655440000")
    val ledgerTransactionId: UUID? = null,
    
    @Schema(description = "Idempotency key for this refund", example = "refund_770e8400_1234567890")
    val idempotencyKey: String? = null,
    
    @Schema(description = "Refund creation timestamp (ISO 8601)", example = "2024-01-15T10:30:00Z")
    val createdAt: String? = null,
    
    @Schema(description = "Refund last update timestamp (ISO 8601)", example = "2024-01-15T10:30:00Z")
    val updatedAt: String? = null,
    
    @Schema(description = "Seller refund breakdown (per-seller amounts) - optional in response", required = false)
    val sellerRefundBreakdown: List<SellerRefundBreakdownDto>? = null,
    
    @Schema(description = "Refund completion timestamp (ISO 8601) - set when state becomes REFUNDED", example = "2024-01-15T10:30:00Z", required = false)
    val refundedAt: String? = null,
    
    @Schema(description = "Error message if the request failed", example = "Refund creation failed: Payment not in CAPTURED state")
    val error: String? = null
) {
    companion object {
        fun fromDomain(refund: Refund): RefundResponseDto {
            return RefundResponseDto(
                id = refund.id,
                paymentId = refund.paymentId,
                refundAmountCents = refund.refundAmountCents,
                platformFeeRefundCents = refund.platformFeeRefundCents,
                netSellerRefundCents = refund.netSellerRefundCents,
                currency = refund.currency,
                state = refund.state.name,
                stripeRefundId = refund.stripeRefundId,
                ledgerTransactionId = refund.ledgerTransactionId,
                idempotencyKey = refund.idempotencyKey,
                createdAt = refund.createdAt.toString(),
                updatedAt = refund.updatedAt.toString(),
                sellerRefundBreakdown = refund.sellerRefundBreakdown?.map {
                    SellerRefundBreakdownDto(
                        sellerId = it.sellerId,
                        refundAmountCents = it.refundAmountCents,
                        platformFeeRefundCents = it.platformFeeRefundCents,
                        netSellerRefundCents = it.netSellerRefundCents
                    )
                },
                refundedAt = null // Note: Refund domain doesn't have refundedAt field, would need to add it if needed
            )
        }
    }
}

/**
 * Seller refund breakdown DTO for refund responses.
 */
@Schema(description = "Seller refund breakdown - one seller's portion of the refund")
data class SellerRefundBreakdownDto(
    @Schema(description = "Seller ID", example = "seller_456")
    val sellerId: String,
    
    @Schema(description = "This seller's refund amount in cents", example = "5000")
    val refundAmountCents: Long,
    
    @Schema(description = "This seller's platform fee refund in cents", example = "500")
    val platformFeeRefundCents: Long,
    
    @Schema(description = "This seller's net refund in cents", example = "4500")
    val netSellerRefundCents: Long
)

/**
 * Response DTO for listing refunds.
 */
@Schema(description = "List of refunds response")
data class ListRefundsResponseDto(
    @Schema(description = "List of refunds")
    val refunds: List<RefundResponseDto>? = null,
    
    @Schema(description = "Error message if the request failed", example = "Payment not found")
    val error: String? = null
)

/**
 * Request DTO for batch refund retrieval.
 */
@Schema(description = "Request to get refunds for multiple payments")
data class BatchRefundsRequestDto(
    @Schema(description = "List of payment IDs to get refunds for", example = "[\"660e8400-e29b-41d4-a716-446655440000\", \"770e8400-e29b-41d4-a716-446655440000\"]")
    val paymentIds: List<UUID>
)

/**
 * Response DTO for batch refund retrieval.
 * Maps payment ID to list of refunds.
 */
@Schema(description = "Batch refunds response - maps payment ID to list of refunds")
data class BatchRefundsResponseDto(
    @Schema(description = "Map of payment ID to list of refunds")
    val refundsByPaymentId: Map<String, List<RefundResponseDto>>,
    
    @Schema(description = "Error message if the request failed", example = "Invalid request")
    val error: String? = null
)
