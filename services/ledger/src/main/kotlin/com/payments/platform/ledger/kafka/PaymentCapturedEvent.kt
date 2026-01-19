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
 * One payment per order - can have multiple sellers via sellerBreakdown.
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
    
    @JsonProperty("orderId")
    val orderId: UUID,
    
    @JsonProperty("buyerId")
    val buyerId: String,
    
    @JsonProperty("grossAmountCents")
    val grossAmountCents: Long,
    
    @JsonProperty("platformFeeCents")
    val platformFeeCents: Long,  // Total platform fee (sum of all seller platform fees)
    
    @JsonProperty("netSellerAmountCents")
    val netSellerAmountCents: Long,  // Total net seller amounts (sum of all seller net amounts)
    
    @JsonProperty("currency")
    val currency: String,
    
    @JsonProperty("stripePaymentIntentId")
    val stripePaymentIntentId: String,
    
    @JsonProperty("sellerBreakdown")
    val sellerBreakdown: List<SellerBreakdownEvent>,  // Per-seller breakdown
    
    @JsonProperty("attempt")
    val attempt: Int,
    
    @JsonProperty("createdAt")
    val createdAt: Instant,
    
    @JsonProperty("payload")
    val payload: Map<String, Any> = emptyMap()
)

/**
 * Seller breakdown event - one per seller in the payment.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SellerBreakdownEvent(
    @JsonProperty("sellerId")
    val sellerId: String,
    
    @JsonProperty("sellerGrossAmountCents")
    val sellerGrossAmountCents: Long,
    
    @JsonProperty("platformFeeCents")
    val platformFeeCents: Long,  // This seller's platform fee
    
    @JsonProperty("netSellerAmountCents")
    val netSellerAmountCents: Long  // This seller's net amount
)
