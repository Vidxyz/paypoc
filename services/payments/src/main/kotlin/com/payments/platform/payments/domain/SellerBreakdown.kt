package com.payments.platform.payments.domain

/**
 * Seller breakdown for order-based payments.
 * 
 * Represents one seller's portion of a payment, including:
 * - The seller's gross amount (their portion of the total order)
 * - The platform fee for this seller (10% of seller's gross)
 * - The net amount the seller will receive (90% of seller's gross)
 */
data class SellerBreakdown(
    val sellerId: String,
    val sellerGrossAmountCents: Long,
    val platformFeeCents: Long,
    val netSellerAmountCents: Long
) {
    init {
        require(sellerGrossAmountCents > 0) { "Seller gross amount must be positive" }
        require(platformFeeCents >= 0) { "Platform fee cannot be negative" }
        require(netSellerAmountCents > 0) { "Net seller amount must be positive" }
        require(sellerGrossAmountCents == platformFeeCents + netSellerAmountCents) {
            "Seller breakdown must balance: sellerGrossAmountCents = platformFeeCents + netSellerAmountCents"
        }
    }
}
