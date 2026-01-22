package models

import (
	"time"

	"github.com/google/uuid"
)

// OrderStatus represents the status of an order
type OrderStatus string

const (
	OrderStatusPending    OrderStatus = "PENDING"
	OrderStatusConfirmed  OrderStatus = "CONFIRMED"
	OrderStatusCancelled  OrderStatus = "CANCELLED"
	OrderStatusProcessing OrderStatus = "PROCESSING"
	OrderStatusShipped    OrderStatus = "SHIPPED"
	OrderStatusDelivered  OrderStatus = "DELIVERED"
)

// Order represents an order in the system
type Order struct {
	ID           uuid.UUID
	CartID       *uuid.UUID // Cart ID that this order was created from
	BuyerID      string
	Status       OrderStatus
	Provisional  bool
	PaymentID    *uuid.UUID
	TotalCents   int64
	Currency     string
	RefundStatus string // NONE, PARTIAL, FULL
	CreatedAt    time.Time
	UpdatedAt    time.Time
	ConfirmedAt  *time.Time
	CancelledAt  *time.Time
}

// OrderItem represents an item in an order
type OrderItem struct {
	ID               uuid.UUID
	OrderID          uuid.UUID
	ProductID        uuid.UUID
	SKU              string
	SellerID         string
	Quantity         int
	PriceCents       int64
	Currency         string
	RefundedQuantity int // Quantity of this item that has been refunded
	ReservationID    *uuid.UUID
	ShipmentID       *uuid.UUID
	CreatedAt        time.Time
}

// Shipment represents a shipment (one per seller)
type Shipment struct {
	ID             uuid.UUID  `json:"id"`
	OrderID        uuid.UUID  `json:"order_id"`
	SellerID       string     `json:"seller_id"`
	Status         string     `json:"status"` // PENDING, PROCESSING, SHIPPED, DELIVERED, CANCELLED
	Provisional    bool       `json:"provisional"`
	TrackingNumber *string    `json:"tracking_number,omitempty"`
	Carrier        *string    `json:"carrier,omitempty"`
	ShippedAt      *time.Time `json:"shipped_at,omitempty"`
	DeliveredAt    *time.Time `json:"delivered_at,omitempty"`
	CreatedAt      time.Time  `json:"created_at"`
	UpdatedAt      time.Time  `json:"updated_at"`
}

// CreateOrderRequest represents a request to create an order
type CreateOrderRequest struct {
	CartID  uuid.UUID  `json:"cart_id" binding:"required"`
	BuyerID string     `json:"buyer_id" binding:"required"`
	Items   []CartItem `json:"items" binding:"required"`
}

// CartItem represents an item from the cart
type CartItem struct {
	ItemID        uuid.UUID  `json:"item_id"`
	ProductID     uuid.UUID  `json:"product_id"`
	SKU           string     `json:"sku"`
	SellerID      string     `json:"seller_id"`
	Quantity      int        `json:"quantity"`
	PriceCents    int64      `json:"price_cents"`
	Currency      string     `json:"currency"`
	ReservationID *uuid.UUID `json:"reservation_id"`
}

// CreateOrderResponse represents the response after creating an order
type CreateOrderResponse struct {
	OrderID      uuid.UUID `json:"order_id"`
	PaymentID    uuid.UUID `json:"payment_id"`
	ClientSecret string    `json:"client_secret"`
	CheckoutURL  string    `json:"checkout_url"`
}

// OrderResponse represents an order in API responses
type OrderResponse struct {
	ID           uuid.UUID           `json:"id"`
	BuyerID      string              `json:"buyer_id"`
	Status       string              `json:"status"`
	TotalCents   int64               `json:"total_cents"`
	Currency     string              `json:"currency"`
	PaymentID    *uuid.UUID          `json:"payment_id,omitempty"`
	RefundStatus string              `json:"refund_status,omitempty"`
	Items        []OrderItemResponse `json:"items"`
	Shipments    []ShipmentResponse  `json:"shipments"`
	CreatedAt    time.Time           `json:"created_at"`
	ConfirmedAt  *time.Time          `json:"confirmed_at,omitempty"`
}

// OrderItemResponse represents an order item in API responses
type OrderItemResponse struct {
	ID               uuid.UUID `json:"id"`
	ProductID        uuid.UUID `json:"product_id"`
	SKU              string    `json:"sku"`
	SellerID         string    `json:"seller_id"`
	Quantity         int       `json:"quantity"`
	RefundedQuantity int       `json:"refunded_quantity,omitempty"`
	PriceCents       int64     `json:"price_cents"`
	Currency         string    `json:"currency"`
}

// ShipmentResponse represents a shipment in API responses
type ShipmentResponse struct {
	ID             uuid.UUID  `json:"id"`
	SellerID       string     `json:"seller_id"`
	Status         string     `json:"status"`
	TrackingNumber *string    `json:"tracking_number,omitempty"`
	Carrier        *string    `json:"carrier,omitempty"`
	ShippedAt      *time.Time `json:"shipped_at,omitempty"`
	DeliveredAt    *time.Time `json:"delivered_at,omitempty"`
}
