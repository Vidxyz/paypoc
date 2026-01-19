package com.payments.platform.payments.kafka

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Seller chargeback breakdown - one per seller in the payment.
 * Used for reference in chargeback events.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SellerChargebackBreakdown(
    @JsonProperty("sellerId")
    val sellerId: String,
    
    @JsonProperty("sellerGrossAmountCents")
    val sellerGrossAmountCents: Long,  // This seller's portion of the original payment
    
    @JsonProperty("platformFeeCents")
    val platformFeeCents: Long,  // This seller's platform fee portion
    
    @JsonProperty("netSellerAmountCents")
    val netSellerAmountCents: Long  // This seller's net amount portion
)

/**
 * Event published when a chargeback (dispute) is won or withdrawn.
 * 
 * This event triggers ledger write to credit money back to STRIPE_CLEARING.
 * Money is returned when platform wins the dispute.
 * 
 * Consumed by:
 * - Ledger Service (to create double-entry transaction crediting STRIPE_CLEARING)
 * 
 * Note: sellerBreakdown is included for reference but no seller-specific ledger entries are needed
 * (money is simply moved from CHARGEBACK_CLEARING to STRIPE_CLEARING).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChargebackWonEvent(
    @JsonProperty("type")
    val type: String = "CHARGEBACK_WON",
    
    @JsonProperty("chargebackId")
    val chargebackId: UUID,
    
    @JsonProperty("paymentId")
    val paymentId: UUID,
    
    @JsonProperty("chargebackAmountCents")
    val chargebackAmountCents: Long,
    
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

