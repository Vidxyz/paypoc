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
	userClient      *UserClient
	catalogClient   *CatalogClient
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
	userClient *UserClient,
	catalogClient *CatalogClient,
	kafkaProducer KafkaProducerInterface,
) *OrderService {
	return &OrderService{
		repo:            repo,
		inventoryClient: inventoryClient,
		paymentClient:   paymentClient,
		cartClient:      cartClient,
		userClient:      userClient,
		catalogClient:   catalogClient,
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
	cartID := &req.CartID
	order := &models.Order{
		ID:          orderID,
		CartID:      cartID,
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

	// Calculate seller breakdowns (one per seller)
	sellerBreakdown := make([]SellerPaymentBreakdown, 0, len(sellerItems))
	for sellerID, items := range sellerItems {
		sellerGrossAmountCents := int64(0)
		for _, item := range items {
			sellerGrossAmountCents += item.PriceCents * int64(item.Quantity)
		}
		sellerBreakdown = append(sellerBreakdown, SellerPaymentBreakdown{
			SellerID:               sellerID,
			SellerGrossAmountCents: sellerGrossAmountCents,
		})
	}

	// Create single payment for entire order (one payment per order, multiple sellers)
	paymentResp, err := s.paymentClient.CreateOrderPayment(ctx, CreateOrderPaymentRequest{
		OrderID:          orderID,
		BuyerID:          req.BuyerID,
		GrossAmountCents: totalCents,
		Currency:         currency,
		SellerBreakdown:  sellerBreakdown,
		Description:      fmt.Sprintf("Payment for order %s", orderID),
	})
	if err != nil {
		// Rollback: cancel order and release inventory
		s.cancelOrder(ctx, orderID)
		return nil, fmt.Errorf("failed to create payment: %w", err)
	}

	// Update order with payment ID
	if err := s.repo.UpdatePaymentID(ctx, orderID, paymentResp.PaymentID); err != nil {
		log.Printf("Warning: failed to update order with payment ID: %v", err)
		// Don't fail the entire operation - payment was created successfully
		// The order can be updated later if needed
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
	if order.CartID != nil {
		go func() {
			if err := s.cartClient.UpdateCartStatus(context.Background(), *order.CartID, "COMPLETED"); err != nil {
				log.Printf("Warning: failed to update cart status: %v", err)
			}
		}()
	}

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
// For SELLER account type, only returns items and shipments belonging to that seller
func (s *OrderService) GetOrder(ctx context.Context, orderID uuid.UUID, accountType, userID string) (*models.OrderResponse, error) {
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

	// For SELLER, filter items to only show their items
	if accountType == "SELLER" {
		filteredItems := make([]models.OrderItem, 0)
		for _, item := range items {
			if item.SellerID == userID {
				filteredItems = append(filteredItems, item)
			}
		}

		// If seller has no items in this order, return not found
		if len(filteredItems) == 0 {
			return nil, fmt.Errorf("order not found or seller has no items in this order")
		}

		items = filteredItems
	}

	// For SELLER, filter shipments to only show their shipments
	if accountType == "SELLER" {
		filteredShipments := make([]models.Shipment, 0)
		for _, shipment := range shipments {
			if shipment.SellerID == userID {
				filteredShipments = append(filteredShipments, shipment)
			}
		}
		shipments = filteredShipments
	}

	// Convert to response
	itemResponses := make([]models.OrderItemResponse, len(items))
	for i, item := range items {
		itemResponses[i] = models.OrderItemResponse{
			ID:               item.ID,
			ProductID:        item.ProductID,
			SKU:              item.SKU,
			SellerID:         item.SellerID,
			Quantity:         item.Quantity,
			RefundedQuantity: item.RefundedQuantity,
			PriceCents:       item.PriceCents,
			Currency:         item.Currency,
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
		ID:           order.ID,
		BuyerID:      order.BuyerID,
		Status:       string(order.Status),
		TotalCents:   order.TotalCents,
		Currency:     order.Currency,
		PaymentID:    order.PaymentID,
		RefundStatus: order.RefundStatus,
		Items:        itemResponses,
		Shipments:    shipmentResponses,
		CreatedAt:    order.CreatedAt,
		ConfirmedAt:  order.ConfirmedAt,
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

	// Fetch product names from catalog service
	productNames := make(map[uuid.UUID]string)
	for _, item := range items {
		if s.catalogClient != nil {
			product, err := s.catalogClient.GetProduct(ctx, item.ProductID)
			if err != nil {
				// Log error but continue - we'll use SKU as fallback
				log.Printf("Failed to fetch product name for %s: %v", item.ProductID, err)
			} else {
				productNames[item.ProductID] = product.Name
			}
		}
	}

	return &OrderForInvoice{
		Order:        order,
		Items:        items,
		ProductNames: productNames,
	}, nil
}

// GetOrderByPaymentID retrieves an order by payment ID
func (s *OrderService) GetOrderByPaymentID(ctx context.Context, paymentID uuid.UUID) (*models.Order, error) {
	order, err := s.repo.GetByPaymentID(ctx, paymentID)
	if err != nil {
		return nil, fmt.Errorf("failed to get order by payment ID: %w", err)
	}
	return order, nil
}

// MarkCartAbandoned marks a cart as ABANDONED via the cart service
func (s *OrderService) MarkCartAbandoned(ctx context.Context, cartID uuid.UUID, reason string) error {
	return s.cartClient.UpdateCartStatus(ctx, cartID, "ABANDONED")
}

// OrderForInvoice contains order and items for invoice generation
type OrderForInvoice struct {
	Order        *models.Order
	Items        []models.OrderItem
	ProductNames map[uuid.UUID]string // Map of product ID to product name
}

// ProcessRefund handles RefundCompletedEvent from payments service
// Updates order items with refunded quantities and creates audit trail
// All operations are wrapped in a transaction for atomicity
func (s *OrderService) ProcessRefund(ctx context.Context, event models.RefundCompletedEvent) error {
	// Begin transaction - all operations must succeed or all will be rolled back
	tx, err := s.repo.BeginTx(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer tx.Rollback(ctx) // Always rollback on error, commit will happen at the end

	// Check idempotency - if this refund was already processed, skip
	exists, err := s.repo.CheckRefundExistsTx(ctx, tx, event.RefundID)
	if err != nil {
		return fmt.Errorf("failed to check refund existence: %w", err)
	}
	if exists {
		log.Printf("Refund %s already processed, skipping (idempotency)", event.RefundID)
		// Transaction will be rolled back by defer, which is fine since we're not making changes
		return nil
	}

	// Validate order exists and matches event (read-only, can use regular method)
	order, err := s.repo.GetByID(ctx, event.OrderID)
	if err != nil {
		return fmt.Errorf("failed to get order %s: %w", event.OrderID, err)
	}

	if order.PaymentID == nil || *order.PaymentID != event.PaymentID {
		return fmt.Errorf("order %s payment ID mismatch: expected %s, got %v", event.OrderID, event.PaymentID, order.PaymentID)
	}

	var allItems []models.OrderItem // Store items for status calculation (fixes point 10)

	// If no order items specified, this might be a full refund
	// For full refunds, we need to get all order items
	if len(event.OrderItemsRefunded) == 0 {
		log.Printf("Refund %s has no order items specified, treating as full refund", event.RefundID)
		// Get all order items and mark them as fully refunded
		allItems, err = s.repo.GetItemsByOrderIDTx(ctx, tx, event.OrderID)
		if err != nil {
			return fmt.Errorf("failed to get order items for full refund: %w", err)
		}

		// Process each item as fully refunded
		for i := range allItems {
			item := &allItems[i]
			refundedItem := models.RefundedOrderItem{
				ID:          uuid.New(),
				RefundID:    event.RefundID,
				OrderID:     event.OrderID,
				OrderItemID: item.ID,
				Quantity:    item.Quantity - item.RefundedQuantity, // Only refund what hasn't been refunded yet
				PriceCents:  item.PriceCents,
				SellerID:    item.SellerID,
				RefundedAt:  time.Now(),
				CreatedAt:   time.Now(),
			}

			// Only process if there's something to refund
			if refundedItem.Quantity > 0 {
				// Validate refunded quantity doesn't exceed original quantity (point 11)
				newRefundedQuantity := item.RefundedQuantity + refundedItem.Quantity
				if newRefundedQuantity > item.Quantity {
					return fmt.Errorf("refunded quantity %d would exceed original quantity %d for order item %s", newRefundedQuantity, item.Quantity, item.ID)
				}

				// Update refunded quantity
				if err := s.repo.UpdateItemRefundedQuantityTx(ctx, tx, item.ID, newRefundedQuantity); err != nil {
					return fmt.Errorf("failed to update refunded quantity for item %s: %w", item.ID, err)
				}

				// Create audit record
				if err := s.repo.CreateRefundedOrderItemTx(ctx, tx, &refundedItem); err != nil {
					return fmt.Errorf("failed to create refunded order item record: %w", err)
				}

				// Update the item in our local slice for status calculation
				item.RefundedQuantity = newRefundedQuantity
			}
		}
	} else {
		// Process partial refund - update specific items
		// First, get all items for this order to calculate status later (fixes point 10)
		allItems, err = s.repo.GetItemsByOrderIDTx(ctx, tx, event.OrderID)
		if err != nil {
			return fmt.Errorf("failed to get order items: %w", err)
		}

		// Create a map for quick lookup
		itemsMap := make(map[uuid.UUID]models.OrderItem)
		for _, item := range allItems {
			itemsMap[item.ID] = item
		}

		// Process each refunded item
		for _, itemRefund := range event.OrderItemsRefunded {
			item, exists := itemsMap[itemRefund.OrderItemID]
			if !exists {
				return fmt.Errorf("order item %s not found", itemRefund.OrderItemID)
			}

			// Validate item belongs to this order
			if item.OrderID != event.OrderID {
				return fmt.Errorf("order item %s does not belong to order %s", itemRefund.OrderItemID, event.OrderID)
			}

			// Validate seller matches
			if item.SellerID != itemRefund.SellerID {
				return fmt.Errorf("seller mismatch for order item %s: expected %s, got %s", itemRefund.OrderItemID, item.SellerID, itemRefund.SellerID)
			}

			// Validate price matches
			if item.PriceCents != itemRefund.PriceCents {
				return fmt.Errorf("price mismatch for order item %s: expected %d, got %d", itemRefund.OrderItemID, item.PriceCents, itemRefund.PriceCents)
			}

			// Validate refund quantity doesn't exceed available quantity
			availableQuantity := item.Quantity - item.RefundedQuantity
			if itemRefund.Quantity > availableQuantity {
				return fmt.Errorf("refund quantity %d exceeds available quantity %d for order item %s", itemRefund.Quantity, availableQuantity, itemRefund.OrderItemID)
			}

			// Validate refunded quantity doesn't exceed original quantity (point 11)
			newRefundedQuantity := item.RefundedQuantity + itemRefund.Quantity
			if newRefundedQuantity > item.Quantity {
				return fmt.Errorf("refunded quantity %d would exceed original quantity %d for order item %s", newRefundedQuantity, item.Quantity, item.ID)
			}

			// Update refunded quantity
			if err := s.repo.UpdateItemRefundedQuantityTx(ctx, tx, item.ID, newRefundedQuantity); err != nil {
				return fmt.Errorf("failed to update refunded quantity for item %s: %w", item.ID, err)
			}

			// Create audit record
			refundedItem := models.RefundedOrderItem{
				ID:          uuid.New(),
				RefundID:    event.RefundID,
				OrderID:     event.OrderID,
				OrderItemID: item.ID,
				Quantity:    itemRefund.Quantity,
				PriceCents:  itemRefund.PriceCents,
				SellerID:    itemRefund.SellerID,
				RefundedAt:  time.Now(),
				CreatedAt:   time.Now(),
			}

			if err := s.repo.CreateRefundedOrderItemTx(ctx, tx, &refundedItem); err != nil {
				return fmt.Errorf("failed to create refunded order item record: %w", err)
			}

			// Update the item in our local slice for status calculation
			item.RefundedQuantity = newRefundedQuantity
			itemsMap[item.ID] = item
		}

		// Update allItems with the modified items
		for i := range allItems {
			if updatedItem, ok := itemsMap[allItems[i].ID]; ok {
				allItems[i] = updatedItem
			}
		}
	}

	// Calculate and update order refund status (point 7 - within transaction)
	// Use allItems we already have (fixes point 10 - no duplicate query)
	refundStatus := "NONE"
	totalItems := 0
	totalRefunded := 0
	for _, item := range allItems {
		totalItems += item.Quantity
		totalRefunded += item.RefundedQuantity
	}

	if totalRefunded == 0 {
		refundStatus = "NONE"
	} else if totalRefunded >= totalItems {
		refundStatus = "FULL"
	} else {
		refundStatus = "PARTIAL"
	}

	// Update refund status within transaction (point 7)
	if err := s.repo.UpdateOrderRefundStatusTx(ctx, tx, event.OrderID, refundStatus); err != nil {
		return fmt.Errorf("failed to update order refund status: %w", err)
	}

	// Commit transaction - all operations succeeded
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	log.Printf("Successfully processed refund %s for order %s (status: %s)", event.RefundID, event.OrderID, refundStatus)
	return nil
}

// ListOrdersResponse represents a paginated list of orders
type ListOrdersResponse struct {
	Orders     []models.OrderResponse `json:"orders"`
	Total      int                    `json:"total"`
	Page       int                    `json:"page"`
	PageSize   int                    `json:"page_size"`
	TotalPages int                    `json:"total_pages"`
}

// ListOrders retrieves orders based on account type
// ADMIN: can see all orders, optionally filtered by buyerID (UUID) or buyerEmail (email)
// BUYER: only their own orders
// SELLER: only orders where they have items (with only their items shown)
func (s *OrderService) ListOrders(ctx context.Context, accountType, userID string, buyerIDFilter *string, buyerEmailFilter *string, authToken string, page, pageSize int) (*ListOrdersResponse, error) {
	// Validate pagination
	if page < 0 {
		page = 0
	}
	if pageSize < 1 {
		pageSize = 20
	}
	if pageSize > 100 {
		pageSize = 100
	}

	offset := page * pageSize

	var orders []models.Order
	var total int
	var err error

	switch accountType {
	case "ADMIN":
		// ADMIN can see all orders, optionally filtered by buyer (UUID or email)
		var finalFilter *string
		if buyerEmailFilter != nil {
			// For email filtering, look up UUID from user service
			user, err := s.userClient.GetUserByEmail(ctx, *buyerEmailFilter, authToken)
			if err != nil {
				// If user not found, return empty results
				log.Printf("User not found for email %s: %v", *buyerEmailFilter, err)
				return &ListOrdersResponse{
					Orders:     []models.OrderResponse{},
					Total:      0,
					Page:       page,
					PageSize:   pageSize,
					TotalPages: 0,
				}, nil
			}
			// Use the user's ID (primary key UUID) for filtering
			// Note: This assumes orders.buyer_id stores the user service's primary key ID
			// If it stores Auth0 user ID instead, we'd need auth0_user_id from user service
			uuidFilter := user.ID.String()
			log.Printf("Email filter %s resolved to user ID: %s", *buyerEmailFilter, uuidFilter)
			finalFilter = &uuidFilter
		} else if buyerIDFilter != nil {
			finalFilter = buyerIDFilter
		}

		orders, err = s.repo.ListOrders(ctx, finalFilter, pageSize, offset)
		if err != nil {
			return nil, fmt.Errorf("failed to list orders: %w", err)
		}
		total, err = s.repo.CountOrders(ctx, finalFilter)
		if err != nil {
			return nil, fmt.Errorf("failed to count orders: %w", err)
		}

	case "BUYER":
		// BUYER can only see their own orders
		buyerID := userID
		orders, err = s.repo.ListOrders(ctx, &buyerID, pageSize, offset)
		if err != nil {
			return nil, fmt.Errorf("failed to list orders: %w", err)
		}
		total, err = s.repo.CountOrders(ctx, &buyerID)
		if err != nil {
			return nil, fmt.Errorf("failed to count orders: %w", err)
		}

	case "SELLER":
		// SELLER can see orders where they have items
		orders, err = s.repo.ListOrdersForSeller(ctx, userID, pageSize, offset)
		if err != nil {
			return nil, fmt.Errorf("failed to list orders for seller: %w", err)
		}
		total, err = s.repo.CountOrdersForSeller(ctx, userID)
		if err != nil {
			return nil, fmt.Errorf("failed to count orders for seller: %w", err)
		}

	default:
		return nil, fmt.Errorf("unsupported account type: %s", accountType)
	}

	// Convert to response format
	orderResponses := make([]models.OrderResponse, 0, len(orders))
	for _, order := range orders {
		// Get items for this order
		items, err := s.repo.GetItemsByOrderID(ctx, order.ID)
		if err != nil {
			log.Printf("Warning: failed to get items for order %s: %v", order.ID, err)
			continue
		}

		// For SELLER, filter items to only show their items
		if accountType == "SELLER" {
			filteredItems := make([]models.OrderItem, 0)
			for _, item := range items {
				if item.SellerID == userID {
					filteredItems = append(filteredItems, item)
				}
			}
			items = filteredItems
		}

		// Get shipments
		shipments, err := s.repo.GetShipmentsByOrderID(ctx, order.ID)
		if err != nil {
			log.Printf("Warning: failed to get shipments for order %s: %v", order.ID, err)
			shipments = []models.Shipment{}
		}

		// For SELLER, filter shipments to only show their shipments
		if accountType == "SELLER" {
			filteredShipments := make([]models.Shipment, 0)
			for _, shipment := range shipments {
				if shipment.SellerID == userID {
					filteredShipments = append(filteredShipments, shipment)
				}
			}
			shipments = filteredShipments
		}

		// Convert items to response
		itemResponses := make([]models.OrderItemResponse, len(items))
		for i, item := range items {
			itemResponses[i] = models.OrderItemResponse{
				ID:               item.ID,
				ProductID:        item.ProductID,
				SKU:              item.SKU,
				SellerID:         item.SellerID,
				Quantity:         item.Quantity,
				RefundedQuantity: item.RefundedQuantity,
				PriceCents:       item.PriceCents,
				Currency:         item.Currency,
			}
		}

		// Convert shipments to response
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

		orderResponses = append(orderResponses, models.OrderResponse{
			ID:           order.ID,
			BuyerID:      order.BuyerID,
			Status:       string(order.Status),
			TotalCents:   order.TotalCents,
			Currency:     order.Currency,
			PaymentID:    order.PaymentID,
			RefundStatus: order.RefundStatus,
			Items:        itemResponses,
			Shipments:    shipmentResponses,
			CreatedAt:    order.CreatedAt,
			ConfirmedAt:  order.ConfirmedAt,
		})
	}

	totalPages := (total + pageSize - 1) / pageSize

	return &ListOrdersResponse{
		Orders:     orderResponses,
		Total:      total,
		Page:       page,
		PageSize:   pageSize,
		TotalPages: totalPages,
	}, nil
}

// GetShipmentByID retrieves a shipment by ID (internal API)
func (s *OrderService) GetShipmentByID(ctx context.Context, shipmentID uuid.UUID) (*models.Shipment, error) {
	return s.repo.GetShipmentByID(ctx, shipmentID)
}

// GetShipmentsByOrderID retrieves all shipments for an order (internal API)
func (s *OrderService) GetShipmentsByOrderID(ctx context.Context, orderID uuid.UUID) ([]models.Shipment, error) {
	return s.repo.GetShipmentsByOrderID(ctx, orderID)
}

// GetShipmentsBySellerID retrieves all shipments for a seller (internal API)
func (s *OrderService) GetShipmentsBySellerID(ctx context.Context, sellerID string, limit, offset int) ([]models.Shipment, error) {
	return s.repo.GetShipmentsBySellerID(ctx, sellerID, limit, offset)
}

// GetShipmentByTrackingNumber retrieves a shipment by tracking number (internal API)
func (s *OrderService) GetShipmentByTrackingNumber(ctx context.Context, trackingNumber string) (*models.Shipment, error) {
	return s.repo.GetShipmentByTrackingNumber(ctx, trackingNumber)
}

// UpdateShipmentStatus updates the status of a shipment (internal API)
func (s *OrderService) UpdateShipmentStatus(ctx context.Context, shipmentID uuid.UUID, status string, shippedAt, deliveredAt *time.Time) (*models.Shipment, error) {
	// Get current shipment to validate it exists
	shipment, err := s.repo.GetShipmentByID(ctx, shipmentID)
	if err != nil {
		return nil, fmt.Errorf("shipment not found: %w", err)
	}

	// Update status
	if err := s.repo.UpdateShipmentStatus(ctx, shipmentID, status, shippedAt, deliveredAt); err != nil {
		return nil, fmt.Errorf("failed to update shipment status: %w", err)
	}

	// Get updated shipment
	updatedShipment, err := s.repo.GetShipmentByID(ctx, shipmentID)
	if err != nil {
		return nil, fmt.Errorf("failed to get updated shipment: %w", err)
	}

	// Recalculate order status based on shipment statuses
	if err := s.recalculateOrderStatus(ctx, shipment.OrderID); err != nil {
		// Log error but don't fail the request
		log.Printf("Warning: Failed to recalculate order status for order %s: %v", shipment.OrderID, err)
	}

	return updatedShipment, nil
}

// UpdateShipmentTracking updates the tracking information for a shipment (internal API)
func (s *OrderService) UpdateShipmentTracking(ctx context.Context, shipmentID uuid.UUID, trackingNumber, carrier string) (*models.Shipment, error) {
	// Update tracking
	if err := s.repo.UpdateShipmentTracking(ctx, shipmentID, trackingNumber, carrier); err != nil {
		return nil, fmt.Errorf("failed to update shipment tracking: %w", err)
	}

	// Get updated shipment
	updatedShipment, err := s.repo.GetShipmentByID(ctx, shipmentID)
	if err != nil {
		return nil, fmt.Errorf("failed to get updated shipment: %w", err)
	}

	return updatedShipment, nil
}

// recalculateOrderStatus recalculates the order status based on shipment statuses
func (s *OrderService) recalculateOrderStatus(ctx context.Context, orderID uuid.UUID) error {
	shipments, err := s.repo.GetShipmentsByOrderID(ctx, orderID)
	if err != nil {
		return fmt.Errorf("failed to get shipments: %w", err)
	}

	if len(shipments) == 0 {
		return nil // No shipments, nothing to recalculate
	}

	// Determine order status based on shipment statuses
	allShipped := true
	allDelivered := true
	anyShipped := false

	for _, shipment := range shipments {
		switch shipment.Status {
		case "SHIPPED", "IN_TRANSIT", "OUT_FOR_DELIVERY":
			anyShipped = true
			allDelivered = false
		case "DELIVERED":
			anyShipped = true
		default:
			allShipped = false
			allDelivered = false
		}
	}

	order, err := s.repo.GetByID(ctx, orderID)
	if err != nil {
		return fmt.Errorf("failed to get order: %w", err)
	}

	var newStatus models.OrderStatus
	if allDelivered {
		newStatus = models.OrderStatusDelivered
	} else if allShipped || anyShipped {
		newStatus = models.OrderStatusShipped
	} else {
		// Keep current status if no shipments are shipped
		return nil
	}

	// Only update if status changed
	if order.Status != newStatus {
		// Use repository method to update order status
		// Note: We need to add an UpdateOrderStatus method to repository, or use existing ConfirmOrder pattern
		// For now, we'll add a simple update method
		if err := s.repo.UpdateOrderStatus(ctx, orderID, newStatus); err != nil {
			return fmt.Errorf("failed to update order status: %w", err)
		}
	}

	return nil
}

// PartialRefundRequest represents a request for a partial refund
type PartialRefundRequest struct {
	OrderItemsToRefund []OrderItemRefundRequest `json:"orderItemsToRefund" binding:"required"`
}

// OrderItemRefundRequest represents an order item to refund
type OrderItemRefundRequest struct {
	OrderItemID uuid.UUID `json:"orderItemId" binding:"required"`
	Quantity    int       `json:"quantity" binding:"required,min=1"`
}

// CreatePartialRefund creates a partial refund for an order (admin-only)
func (s *OrderService) CreatePartialRefund(ctx context.Context, orderID uuid.UUID, req PartialRefundRequest) error {
	// Get order to verify it exists and is confirmed
	order, err := s.repo.GetByID(ctx, orderID)
	if err != nil {
		return fmt.Errorf("order not found: %w", err)
	}

	if order.Status != models.OrderStatusConfirmed {
		return fmt.Errorf("only confirmed orders can be refunded")
	}

	if order.PaymentID == nil {
		return fmt.Errorf("order has no payment ID")
	}

	// Get all order items
	allItems, err := s.repo.GetItemsByOrderID(ctx, orderID)
	if err != nil {
		return fmt.Errorf("failed to get order items: %w", err)
	}

	// Create a map for quick lookup
	itemsMap := make(map[uuid.UUID]models.OrderItem)
	for _, item := range allItems {
		itemsMap[item.ID] = item
	}

	// Validate and build refund request
	orderItemsToRefund := make([]OrderItemRefundForPayment, 0, len(req.OrderItemsToRefund))
	for _, itemRefund := range req.OrderItemsToRefund {
		item, exists := itemsMap[itemRefund.OrderItemID]
		if !exists {
			return fmt.Errorf("order item %s not found in order", itemRefund.OrderItemID)
		}

		// Validate quantity
		availableQuantity := item.Quantity - item.RefundedQuantity
		if itemRefund.Quantity > availableQuantity {
			return fmt.Errorf("refund quantity %d exceeds available quantity %d for order item %s", itemRefund.Quantity, availableQuantity, itemRefund.OrderItemID)
		}

		if itemRefund.Quantity <= 0 {
			return fmt.Errorf("refund quantity must be greater than 0")
		}

		orderItemsToRefund = append(orderItemsToRefund, OrderItemRefundForPayment{
			OrderItemID: itemRefund.OrderItemID,
			Quantity:    itemRefund.Quantity,
			SellerID:    item.SellerID,
			PriceCents:  item.PriceCents,
		})
	}

	if len(orderItemsToRefund) == 0 {
		return fmt.Errorf("no items to refund")
	}

	// Call payments service internal API for partial refund
	// OrderItemRefundForPayment is defined in http_clients.go (same package)
	err = s.paymentClient.CreatePartialRefund(ctx, orderID, orderItemsToRefund)
	if err != nil {
		return fmt.Errorf("failed to create partial refund: %w", err)
	}

	return nil
}

// CreateFullRefund creates a full refund for an order (admin-only)
func (s *OrderService) CreateFullRefund(ctx context.Context, orderID uuid.UUID) error {
	// Get order to verify it exists and is confirmed
	order, err := s.repo.GetByID(ctx, orderID)
	if err != nil {
		return fmt.Errorf("order not found: %w", err)
	}

	if order.Status != models.OrderStatusConfirmed {
		return fmt.Errorf("only confirmed orders can be refunded")
	}

	if order.PaymentID == nil {
		return fmt.Errorf("order has no payment ID")
	}

	// Check if already fully refunded
	if order.RefundStatus == "FULL" {
		return fmt.Errorf("order is already fully refunded")
	}

	// Call payments service internal API for full refund
	err = s.paymentClient.CreateFullRefund(ctx, orderID)
	if err != nil {
		return fmt.Errorf("failed to create full refund: %w", err)
	}

	return nil
}

// CreatePaymentRequest represents a request to create a payment
type CreatePaymentRequest struct {
	BuyerID          string     `json:"buyerId"`
	GrossAmountCents int64      `json:"grossAmountCents"`
	Currency         string     `json:"currency"`
	OrderID          *uuid.UUID `json:"orderId,omitempty"`
}
