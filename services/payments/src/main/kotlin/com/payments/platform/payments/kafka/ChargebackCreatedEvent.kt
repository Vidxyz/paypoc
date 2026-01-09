package com.payments.platform.payments.kafka

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Event published when a chargeback (dispute) is created.
 * 
 * This event triggers ledger write to debit money from STRIPE_CLEARING.
 * Money is debited immediately when dispute is created.
 * 
 * Consumed by:
 * - Ledger Service (to create double-entry transaction debiting STRIPE_CLEARING)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChargebackCreatedEvent(
    @JsonProperty("type")
    val type: String = "CHARGEBACK_CREATED",
    
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
    
    @JsonProperty("stripeChargeId")
    val stripeChargeId: String,
    
    @JsonProperty("stripePaymentIntentId")
    val stripePaymentIntentId: String,
    
    @JsonProperty("reason")
    val reason: String?,
    
    @JsonProperty("idempotencyKey")
    val idempotencyKey: String,
    
    @JsonProperty("buyerId")
    val buyerId: String,
    
    @JsonProperty("sellerId")
    val sellerId: String
)

