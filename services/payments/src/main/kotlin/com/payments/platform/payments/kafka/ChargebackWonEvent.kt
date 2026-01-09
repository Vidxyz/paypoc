package com.payments.platform.payments.kafka

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Event published when a chargeback (dispute) is won or withdrawn.
 * 
 * This event triggers ledger write to credit money back to STRIPE_CLEARING.
 * Money is returned when platform wins the dispute.
 * 
 * Consumed by:
 * - Ledger Service (to create double-entry transaction crediting STRIPE_CLEARING)
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
    
    @JsonProperty("sellerId")
    val sellerId: String
)

