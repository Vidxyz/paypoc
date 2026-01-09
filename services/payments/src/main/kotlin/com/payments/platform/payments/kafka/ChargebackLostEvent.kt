package com.payments.platform.payments.kafka

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Event published when a chargeback (dispute) is lost.
 * 
 * This event triggers ledger write to reduce seller liability and platform revenue.
 * Money is permanently debited when platform loses the dispute.
 * 
 * Consumed by:
 * - Ledger Service (to create double-entry transaction reducing SELLER_PAYABLE and BUYIT_REVENUE)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChargebackLostEvent(
    @JsonProperty("type")
    val type: String = "CHARGEBACK_LOST",
    
    @JsonProperty("chargebackId")
    val chargebackId: UUID,
    
    @JsonProperty("paymentId")
    val paymentId: UUID,
    
    @JsonProperty("chargebackAmountCents")
    val chargebackAmountCents: Long,
    
    @JsonProperty("platformFeeCents")
    val platformFeeCents: Long,
    
    @JsonProperty("netSellerAmountCents")
    val netSellerAmountCents: Long,
    
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

