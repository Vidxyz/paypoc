package service

import (
	"context"
	"fmt"
	"log"
	"time"

	"github.com/google/uuid"
	"github.com/payments-platform/order-service/internal/models"
	"github.com/payments-platform/order-service/internal/repository"
)

type OrderService struct {
	repo            *repository.OrderRepository
	inventoryClient *InventoryClient
	paymentClient   *PaymentClient
	cartClient      *CartClient
	kafkaProducer   KafkaProducerInterface
}

// KafkaProducerInterface defines the interface for Kafka producer
type KafkaProducerInterface interface {
	PublishOrderConfirmedEvent(ctx context.Context, event models.OrderConfirmedEvent) error
}

func NewOrderService(
	repo *repository.OrderRepository,
	inventoryClient *InventoryClient,
	paymentClient *PaymentClient,
	cartClient *CartClient,
	kafkaProducer KafkaProducerInterface,
) *OrderService {
	return &OrderService{
		repo:            repo,
		inventoryClient: inventoryClient,
		paymentClient:   paymentClient,
		cartClient:      cartClient,
		kafkaProducer:   kafkaProducer,
	}
}

// CreateProvisionalOrder creates a provisional order from cart data
func (s *OrderService) CreateProvisionalOrder(ctx context.Context, req models.CreateOrderRequest) (*models.CreateOrderResponse, error) {
	// Calculate total
	var totalCents int64
	var currency string
	for _, item := range req.Items {
		if currency == "" {
			currency = item.Currency
		}
		totalCents += item.PriceCents * int64(item.Quantity)
	}

	// Create order
	orderID := uuid.New()
	now := time.Now()
	order := &models.Order{
		ID:          orderID,
		BuyerID:     req.BuyerID,
		Status:      models.OrderStatusPending,
		Provisional: true,
		TotalCents:  totalCents,
		Currency:    currency,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	// Group items by seller for shipments
	sellerItems := make(map[string][]models.CartItem)
	for _, item := range req.Items {
		sellerItems[item.SellerID] = append(sellerItems[item.SellerID], item)
	}

	// Create shipments (one per seller)
	var shipments []models.Shipment
	var orderItems []models.OrderItem
	for sellerID, items := range sellerItems {
		shipmentID := uuid.New()
		shipment := models.Shipment{
			ID:          shipmentID,
			OrderID:     orderID,
			SellerID:    sellerID,
			Status:      "PENDING",
			Provisional: true,
			CreatedAt:   now,
			UpdatedAt:   now,
		}
		shipments = append(shipments, shipment)

		// Create order items and link to shipment
		for _, cartItem := range items {
			orderItem := models.OrderItem{
				ID:            uuid.New(),
				OrderID:       orderID,
				ProductID:     cartItem.ProductID,
				SKU:           cartItem.SKU,
				SellerID:      cartItem.SellerID,
				Quantity:      cartItem.Quantity,
				PriceCents:    cartItem.PriceCents,
				Currency:      cartItem.Currency,
				ReservationID: cartItem.ReservationID,
				ShipmentID:    &shipmentID,
				CreatedAt:     now,
			}
			orderItems = append(orderItems, orderItem)
		}
	}

	// Start transaction (we'll use a simple approach - rollback on any error)
	// In production, you'd want proper transaction handling
	if err := s.repo.Create(ctx, order); err != nil {
		return nil, fmt.Errorf("failed to create order: %w", err)
	}

	if err := s.repo.CreateItems(ctx, orderItems); err != nil {
		return nil, fmt.Errorf("failed to create order items: %w", err)
	}

	if err := s.repo.CreateShipments(ctx, shipments); err != nil {
		return nil, fmt.Errorf("failed to create shipments: %w", err)
	}

	// Allocate inventory (HARD locks)
	for _, item := range orderItems {
		if item.ReservationID != nil {
			// Convert soft reservation to hard allocation
			err := s.inventoryClient.CreateHardAllocation(ctx, *item.ReservationID, orderID, item.Quantity)
			if err != nil {
				// Rollback: cancel order and release any allocated inventory
				s.cancelOrder(ctx, orderID)
				return nil, fmt.Errorf("failed to allocate inventory for item %s: %w", item.SKU, err)
			}
		}
	}

	// Create payment
	paymentResp, err := s.paymentClient.CreatePayment(ctx, CreatePaymentRequest{
		BuyerID:          req.BuyerID,
		GrossAmountCents: totalCents,
		Currency:         currency,
		OrderID:          &orderID,
	})
	if err != nil {
		// Rollback: cancel order and release inventory
		s.cancelOrder(ctx, orderID)
		return nil, fmt.Errorf("failed to create payment: %w", err)
	}

	// Update order with payment ID
	order.PaymentID = &paymentResp.PaymentID
	if err := s.repo.Create(ctx, order); err != nil {
		log.Printf("Warning: failed to update order with payment ID: %v", err)
	}

	return &models.CreateOrderResponse{
		OrderID:      orderID,
		PaymentID:    paymentResp.PaymentID,
		ClientSecret: paymentResp.ClientSecret,
		CheckoutURL:  fmt.Sprintf("https://buyit.local/checkout?orderId=%s", orderID),
	}, nil
}

// ConfirmOrder confirms an order after payment is captured
func (s *OrderService) ConfirmOrder(ctx context.Context, paymentID uuid.UUID) error {
	// Get order by payment ID
	order, err := s.repo.GetByPaymentID(ctx, paymentID)
	if err != nil {
		return fmt.Errorf("failed to get order by payment ID: %w", err)
	}

	if order.Status != models.OrderStatusPending {
		log.Printf("Order %s already confirmed or cancelled, status: %s", order.ID, order.Status)
		return nil
	}

	// Get order items
	items, err := s.repo.GetItemsByOrderID(ctx, order.ID)
	if err != nil {
		return fmt.Errorf("failed to get order items: %w", err)
	}

	// Confirm inventory sales
	for _, item := range items {
		if item.ReservationID != nil {
			err := s.inventoryClient.ConfirmSale(ctx, *item.ReservationID)
			if err != nil {
				log.Printf("Warning: failed to confirm sale for reservation %s: %v", *item.ReservationID, err)
				// Continue with other items - we can retry later
			}
		}
	}

	// Update order status
	confirmedAt := time.Now()
	if err := s.repo.ConfirmOrder(ctx, order.ID, confirmedAt); err != nil {
		return fmt.Errorf("failed to confirm order: %w", err)
	}

	// Update shipments to non-provisional
	if err := s.repo.UpdateShipmentProvisional(ctx, order.ID, false); err != nil {
		log.Printf("Warning: failed to update shipment provisional status: %v", err)
	}

	// Update cart status (async - don't fail if this fails)
	go func() {
		if err := s.cartClient.UpdateCartStatus(context.Background(), order.BuyerID, "COMPLETED"); err != nil {
			log.Printf("Warning: failed to update cart status: %v", err)
		}
	}()

	// Publish OrderConfirmedEvent
	event := models.OrderConfirmedEvent{
		EventID:    uuid.New(),
		OrderID:    order.ID,
		BuyerID:    order.BuyerID,
		PaymentID:  paymentID,
		TotalCents: order.TotalCents,
		Currency:   order.Currency,
		CreatedAt:  confirmedAt,
		Type:       "ORDER_CONFIRMED",
	}
	if err := s.kafkaProducer.PublishOrderConfirmedEvent(ctx, event); err != nil {
		log.Printf("Warning: failed to publish OrderConfirmedEvent: %v", err)
	}

	return nil
}

// cancelOrder cancels an order and releases inventory
func (s *OrderService) cancelOrder(ctx context.Context, orderID uuid.UUID) {
	_, err := s.repo.GetByID(ctx, orderID)
	if err != nil {
		log.Printf("Error getting order for cancellation: %v", err)
		return
	}

	items, err := s.repo.GetItemsByOrderID(ctx, orderID)
	if err != nil {
		log.Printf("Error getting items for cancellation: %v", err)
		return
	}

	// Release inventory
	for _, item := range items {
		if item.ReservationID != nil {
			if err := s.inventoryClient.ReleaseReservation(ctx, *item.ReservationID); err != nil {
				log.Printf("Warning: failed to release reservation %s: %v", *item.ReservationID, err)
			}
		}
	}

	// Cancel order
	if err := s.repo.CancelOrder(ctx, orderID, time.Now()); err != nil {
		log.Printf("Error cancelling order: %v", err)
	}
}

// GetOrder retrieves an order by ID
func (s *OrderService) GetOrder(ctx context.Context, orderID uuid.UUID) (*models.OrderResponse, error) {
	order, err := s.repo.GetByID(ctx, orderID)
	if err != nil {
		return nil, fmt.Errorf("failed to get order: %w", err)
	}

	items, err := s.repo.GetItemsByOrderID(ctx, orderID)
	if err != nil {
		return nil, fmt.Errorf("failed to get order items: %w", err)
	}

	shipments, err := s.repo.GetShipmentsByOrderID(ctx, orderID)
	if err != nil {
		return nil, fmt.Errorf("failed to get shipments: %w", err)
	}

	// Convert to response
	itemResponses := make([]models.OrderItemResponse, len(items))
	for i, item := range items {
		itemResponses[i] = models.OrderItemResponse{
			ID:         item.ID,
			ProductID:  item.ProductID,
			SKU:        item.SKU,
			SellerID:   item.SellerID,
			Quantity:   item.Quantity,
			PriceCents: item.PriceCents,
			Currency:   item.Currency,
		}
	}

	shipmentResponses := make([]models.ShipmentResponse, len(shipments))
	for i, shipment := range shipments {
		shipmentResponses[i] = models.ShipmentResponse{
			ID:             shipment.ID,
			SellerID:       shipment.SellerID,
			Status:         shipment.Status,
			TrackingNumber: shipment.TrackingNumber,
			Carrier:        shipment.Carrier,
			ShippedAt:      shipment.ShippedAt,
			DeliveredAt:    shipment.DeliveredAt,
		}
	}

	return &models.OrderResponse{
		ID:          order.ID,
		BuyerID:     order.BuyerID,
		Status:      string(order.Status),
		TotalCents:  order.TotalCents,
		Currency:    order.Currency,
		Items:       itemResponses,
		Shipments:   shipmentResponses,
		CreatedAt:   order.CreatedAt,
		ConfirmedAt: order.ConfirmedAt,
	}, nil
}

// GetOrderForInvoice retrieves order details needed for invoice generation
func (s *OrderService) GetOrderForInvoice(ctx context.Context, orderID uuid.UUID) (*OrderForInvoice, error) {
	order, err := s.repo.GetByID(ctx, orderID)
	if err != nil {
		return nil, fmt.Errorf("failed to get order: %w", err)
	}

	items, err := s.repo.GetItemsByOrderID(ctx, orderID)
	if err != nil {
		return nil, fmt.Errorf("failed to get order items: %w", err)
	}

	return &OrderForInvoice{
		Order: order,
		Items: items,
	}, nil
}

// OrderForInvoice contains order and items for invoice generation
type OrderForInvoice struct {
	Order *models.Order
	Items []models.OrderItem
}

// CreatePaymentRequest represents a request to create a payment
type CreatePaymentRequest struct {
	BuyerID          string     `json:"buyerId"`
	GrossAmountCents int64      `json:"grossAmountCents"`
	Currency         string     `json:"currency"`
	OrderID          *uuid.UUID `json:"orderId,omitempty"`
}
