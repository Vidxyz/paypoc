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
