package com.payments.platform.payments.kafka

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Event published when a refund is completed (after Stripe webhook confirms refund).
 * 
 * This event triggers ledger write to reverse the original payment transaction.
 * 
 * For partial refunds with multiple sellers, includes sellerRefundBreakdown.
 * 
 * Consumed by:
 * - Ledger Service (to create reversed double-entry transaction)
 * - Order Service (to track refunded order items)
 */
data class RefundCompletedEvent(
    @JsonProperty("type")
    val type: String = "REFUND_COMPLETED",
    
    @JsonProperty("refundId")
    val refundId: UUID,
    
    @JsonProperty("paymentId")
    val paymentId: UUID,
    
    @JsonProperty("orderId")
    val orderId: UUID,
    
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
    
    @JsonProperty("sellerRefundBreakdown")
    val sellerRefundBreakdown: List<SellerRefundBreakdownEvent>? = null,  // Per-seller refund breakdown (for partial refunds)
    
    @JsonProperty("orderItemsRefunded")
    val orderItemsRefunded: List<OrderItemRefundInfo>? = null  // Which order items were refunded (for order service tracking)
)

/**
 * Seller refund breakdown event - one per seller in the refund.
 */
data class SellerRefundBreakdownEvent(
    @JsonProperty("sellerId")
    val sellerId: String,
    
    @JsonProperty("refundAmountCents")
    val refundAmountCents: Long,
    
    @JsonProperty("platformFeeRefundCents")
    val platformFeeRefundCents: Long,  // This seller's platform fee refund
    
    @JsonProperty("netSellerRefundCents")
    val netSellerRefundCents: Long  // This seller's net refund
)

/**
 * Order item refund info - specifies which order items were refunded.
 */
data class OrderItemRefundInfo(
    @JsonProperty("orderItemId")
    val orderItemId: UUID,
    
    @JsonProperty("quantity")
    val quantity: Int,
    
    @JsonProperty("sellerId")
    val sellerId: String,
    
    @JsonProperty("priceCents")
    val priceCents: Long
)
