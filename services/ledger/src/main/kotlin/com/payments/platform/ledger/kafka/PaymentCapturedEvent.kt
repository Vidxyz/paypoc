package com.payments.platform.ledger.kafka

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * PaymentCapturedEvent - published by Payments Service after Stripe webhook confirms capture.
 * 
 * This event triggers the ledger write (double-entry bookkeeping).
 * 
 * Note: The JSON includes a "type" field from PaymentMessage, which we ignore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentCapturedEvent(
    @JsonProperty("eventId")
    val eventId: UUID,
    
    @JsonProperty("paymentId")
    val paymentId: UUID,
    
    @JsonProperty("idempotencyKey")
    val idempotencyKey: String,
    
    @JsonProperty("buyerId")
    val buyerId: String,
    
    @JsonProperty("sellerId")
    val sellerId: String,
    
    @JsonProperty("grossAmountCents")
    val grossAmountCents: Long,
    
    @JsonProperty("platformFeeCents")
    val platformFeeCents: Long,
    
    @JsonProperty("netSellerAmountCents")
    val netSellerAmountCents: Long,
    
    @JsonProperty("currency")
    val currency: String,
    
    @JsonProperty("stripePaymentIntentId")
    val stripePaymentIntentId: String,
    
    @JsonProperty("attempt")
    val attempt: Int,
    
    @JsonProperty("createdAt")
    val createdAt: Instant,
    
    @JsonProperty("payload")
    val payload: Map<String, Any> = emptyMap()
)

