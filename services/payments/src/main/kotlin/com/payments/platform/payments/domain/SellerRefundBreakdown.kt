package com.payments.platform.payments.domain

/**
 * Seller refund breakdown for partial refunds.
 * 
 * Represents one seller's portion of a refund, including:
 * - The seller's refund amount (their portion of the total refund)
 * - The platform fee refund for this seller (10% of seller's refund)
 * - The net amount refunded from this seller (90% of seller's refund)
 */
data class SellerRefundBreakdown(
    val sellerId: String,
    val refundAmountCents: Long,
    val platformFeeRefundCents: Long,
    val netSellerRefundCents: Long
) {
    init {
        require(refundAmountCents > 0) { "Refund amount must be positive" }
        require(platformFeeRefundCents >= 0) { "Platform fee refund cannot be negative" }
        require(netSellerRefundCents > 0) { "Net seller refund amount must be positive" }
        require(refundAmountCents == platformFeeRefundCents + netSellerRefundCents) {
            "Seller refund breakdown must balance: refundAmountCents = platformFeeRefundCents + netSellerRefundCents"
        }
    }
}
