package com.payments.platform.payments.kafka

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Event published when a chargeback (dispute) is closed with warning_closed status.
 * 
 * This event triggers ledger write to credit BOTH the chargeback amount AND dispute fee
 * back to STRIPE_CLEARING. Unlike WON, the dispute fee is also refunded.
 * 
 * Consumed by:
 * - Ledger Service (to create double-entry transaction crediting STRIPE_CLEARING for both amount and fee)
 * 
 * Note: sellerBreakdown is included for reference but no seller-specific ledger entries are needed
 * (money is simply moved from CHARGEBACK_CLEARING to STRIPE_CLEARING).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChargebackWarningClosedEvent(
    @JsonProperty("type")
    val type: String = "CHARGEBACK_WARNING_CLOSED",
    
    @JsonProperty("chargebackId")
    val chargebackId: UUID,
    
    @JsonProperty("paymentId")
    val paymentId: UUID,
    
    @JsonProperty("chargebackAmountCents")
    val chargebackAmountCents: Long,
    
    @JsonProperty("disputeFeeCents")
    val disputeFeeCents: Long,
    
    @JsonProperty("currency")
    val currency: String,
    
    @JsonProperty("stripeDisputeId")
    val stripeDisputeId: String,
    
    @JsonProperty("stripePaymentIntentId")
    val stripePaymentIntentId: String,
    
    @JsonProperty("idempotencyKey")
    val idempotencyKey: String,
    
    @JsonProperty("buyerId")
    val buyerId: String,
    
    @JsonProperty("sellerBreakdown")
    val sellerBreakdown: List<SellerChargebackBreakdown>  // Per-seller breakdown (for reference)
)

