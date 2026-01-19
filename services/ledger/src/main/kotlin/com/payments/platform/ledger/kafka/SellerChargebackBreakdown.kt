package com.payments.platform.ledger.kafka

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Seller chargeback breakdown - one per seller in the payment.
 * Matches the structure from payments service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SellerChargebackBreakdown(
    val sellerId: String,
    val sellerGrossAmountCents: Long,
    val platformFeeCents: Long,
    val netSellerAmountCents: Long
)
