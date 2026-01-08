package com.payments.platform.payments.kafka

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Event published when a refund is completed (after Stripe webhook confirms refund).
 * 
 * This event triggers ledger write to reverse the original payment transaction.
 * 
 * Consumed by:
 * - Ledger Service (to create reversed double-entry transaction)
 */
data class RefundCompletedEvent(
    @JsonProperty("type")
    val type: String = "REFUND_COMPLETED",
    
    @JsonProperty("refundId")
    val refundId: UUID,
    
    @JsonProperty("paymentId")
    val paymentId: UUID,
    
    @JsonProperty("refundAmountCents")
    val refundAmountCents: Long,
    
    @JsonProperty("platformFeeRefundCents")
    val platformFeeRefundCents: Long,
    
    @JsonProperty("netSellerRefundCents")
    val netSellerRefundCents: Long,
    
    @JsonProperty("currency")
    val currency: String,
    
    @JsonProperty("stripeRefundId")
    val stripeRefundId: String,
    
    @JsonProperty("stripePaymentIntentId")
    val stripePaymentIntentId: String,
    
    @JsonProperty("idempotencyKey")
    val idempotencyKey: String,
    
    @JsonProperty("buyerId")
    val buyerId: String,
    
    @JsonProperty("sellerId")
    val sellerId: String
)

