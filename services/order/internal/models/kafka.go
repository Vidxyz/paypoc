package models

import (
	"time"

	"github.com/google/uuid"
)

// PaymentCapturedEvent represents the Kafka event when payment is captured
type PaymentCapturedEvent struct {
	EventID               uuid.UUID              `json:"eventId"`
	PaymentID             uuid.UUID              `json:"paymentId"`
	IdempotencyKey        string                 `json:"idempotencyKey"`
	Type                  string                 `json:"type"`
	BuyerID               string                 `json:"buyerId"`
	SellerID              string                 `json:"sellerId"`
	GrossAmountCents      int64                  `json:"grossAmountCents"`
	PlatformFeeCents      int64                  `json:"platformFeeCents"`
	NetSellerAmountCents  int64                  `json:"netSellerAmountCents"`
	Currency              string                 `json:"currency"`
	StripePaymentIntentID string                 `json:"stripePaymentIntentId"`
	Attempt               int                    `json:"attempt"`
	CreatedAt             time.Time              `json:"createdAt"`
	Payload               map[string]interface{} `json:"payload"`
}

// OrderConfirmedEvent represents the Kafka event when order is confirmed
type OrderConfirmedEvent struct {
	EventID    uuid.UUID `json:"eventId"`
	OrderID    uuid.UUID `json:"orderId"`
	BuyerID    string    `json:"buyerId"`
	PaymentID  uuid.UUID `json:"paymentId"`
	TotalCents int64     `json:"totalCents"`
	Currency   string    `json:"currency"`
	CreatedAt  time.Time `json:"createdAt"`
	Type       string    `json:"type"`
}

// RefundCompletedEvent represents the Kafka event when a refund is completed
type RefundCompletedEvent struct {
	Type                   string                  `json:"type"`
	RefundID               uuid.UUID               `json:"refundId"`
	PaymentID              uuid.UUID               `json:"paymentId"`
	OrderID                uuid.UUID               `json:"orderId"`
	RefundAmountCents      int64                   `json:"refundAmountCents"`
	PlatformFeeRefundCents int64                   `json:"platformFeeRefundCents"`
	NetSellerRefundCents   int64                   `json:"netSellerRefundCents"`
	Currency               string                  `json:"currency"`
	StripeRefundID         string                  `json:"stripeRefundId"`
	StripePaymentIntentID  string                  `json:"stripePaymentIntentId"`
	IdempotencyKey         string                  `json:"idempotencyKey"`
	BuyerID                string                  `json:"buyerId"`
	SellerRefundBreakdown  []SellerRefundBreakdown `json:"sellerRefundBreakdown"`
	OrderItemsRefunded     []OrderItemRefundInfo   `json:"orderItemsRefunded"`
}

// SellerRefundBreakdown represents a seller's refund breakdown
type SellerRefundBreakdown struct {
	SellerID               string `json:"sellerId"`
	RefundAmountCents      int64  `json:"refundAmountCents"`
	PlatformFeeRefundCents int64  `json:"platformFeeRefundCents"`
	NetSellerRefundCents   int64  `json:"netSellerRefundCents"`
}

// OrderItemRefundInfo represents an order item that was refunded
type OrderItemRefundInfo struct {
	OrderItemID uuid.UUID `json:"orderItemId"`
	Quantity    int       `json:"quantity"`
	SellerID    string    `json:"sellerId"`
	PriceCents  int64     `json:"priceCents"`
}

// RefundedOrderItem represents a refunded order item in the audit trail
type RefundedOrderItem struct {
	ID          uuid.UUID
	RefundID    uuid.UUID
	OrderID     uuid.UUID
	OrderItemID uuid.UUID
	Quantity    int
	PriceCents  int64
	SellerID    string
	RefundedAt  time.Time
	CreatedAt   time.Time
}

// PaymentFailedEvent represents the Kafka event when payment fails
type PaymentFailedEvent struct {
	EventID        uuid.UUID `json:"eventId"`
	PaymentID      uuid.UUID `json:"paymentId"`
	IdempotencyKey string    `json:"idempotencyKey"`
	Type           string    `json:"type"`
	Reason         string    `json:"reason"`
	Attempt        int       `json:"attempt"`
	CreatedAt      time.Time `json:"createdAt"`
}
