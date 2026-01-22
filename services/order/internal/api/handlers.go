package api

import (
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/payments-platform/order-service/internal/auth"
	"github.com/payments-platform/order-service/internal/invoice"
	"github.com/payments-platform/order-service/internal/models"
	"github.com/payments-platform/order-service/internal/service"
)

type OrderHandler struct {
	orderService *service.OrderService
	invoiceGen   *invoice.InvoiceGenerator
}

func NewOrderHandler(orderService *service.OrderService, invoiceGen *invoice.InvoiceGenerator) *OrderHandler {
	return &OrderHandler{
		orderService: orderService,
		invoiceGen:   invoiceGen,
	}
}

// SwaggerUI serves the Swagger UI page
func (h *OrderHandler) SwaggerUI(c *gin.Context) {
	SwaggerUI(c)
}

// OpenAPIJSON serves the OpenAPI JSON specification
func (h *OrderHandler) OpenAPIJSON(c *gin.Context) {
	OpenAPIJSON(c)
}

// CreateOrder handles POST /internal/orders
// Creates a provisional order from cart items. Allocates inventory, creates payment, and returns checkout URL.
func (h *OrderHandler) CreateOrder(c *gin.Context) {
	var req models.CreateOrderRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.orderService.CreateProvisionalOrder(c.Request.Context(), req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, resp)
}

// GetOrder handles GET /api/orders/:id
// Retrieves order details including items and shipments
// Requires: BUYER (can only access own orders), ADMIN (can access any order), or SELLER (can access orders with their items)
func (h *OrderHandler) GetOrder(c *gin.Context) {
	orderIDStr := c.Param("id")
	orderID, err := uuid.Parse(orderIDStr)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid order ID"})
		return
	}

	// Get user info from JWT middleware
	userID, ok := auth.GetUserID(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "user ID not found in token"})
		return
	}

	accountType, ok := auth.GetAccountType(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "account type not found in token"})
		return
	}

	order, err := h.orderService.GetOrder(c.Request.Context(), orderID, accountType, userID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "order not found"})
		return
	}

	// Authorization: BUYER can only access their own orders, ADMIN can access any order
	// SELLER authorization is handled in GetOrder service method (checks if seller has items in order)
	if accountType == auth.AccountTypeBuyer && order.BuyerID != userID {
		c.JSON(http.StatusForbidden, gin.H{"error": "access denied: you can only access your own orders"})
		return
	}

	c.JSON(http.StatusOK, order)
}

// GetInvoice handles GET /api/orders/:id/invoice
// Generates and downloads invoice PDF for a confirmed order
// Requires: BUYER (can only access own orders) or ADMIN (can access any order)
func (h *OrderHandler) GetInvoice(c *gin.Context) {
	orderIDStr := c.Param("id")
	orderID, err := uuid.Parse(orderIDStr)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid order ID"})
		return
	}

	// Get user info from JWT middleware
	userID, ok := auth.GetUserID(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "user ID not found in token"})
		return
	}

	accountType, ok := auth.GetAccountType(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "account type not found in token"})
		return
	}

	// Get order (for invoice, we need full order details, not filtered)
	// For sellers, we still check if they have items, but invoice generation uses GetOrderForInvoice which gets all items
	orderResp, err := h.orderService.GetOrder(c.Request.Context(), orderID, accountType, userID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "order not found"})
		return
	}

	// Authorization: BUYER can only access their own orders, ADMIN can access any order
	// SELLER authorization is handled in GetOrder service method
	if accountType == auth.AccountTypeBuyer && orderResp.BuyerID != userID {
		c.JSON(http.StatusForbidden, gin.H{"error": "access denied: you can only access your own orders"})
		return
	}

	// Only confirmed orders can have invoices
	if orderResp.Status != string(models.OrderStatusConfirmed) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invoice only available for confirmed orders"})
		return
	}

	// Get order details for invoice generation
	order, err := h.orderService.GetOrderForInvoice(c.Request.Context(), orderID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get order details"})
		return
	}

	// Generate invoice
	buf, err := h.invoiceGen.GenerateInvoice(order.Order, order.Items, order.ProductNames)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to generate invoice"})
		return
	}

	// Set headers and return PDF
	c.Header("Content-Type", "application/pdf")
	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=invoice-%s.pdf", orderID))
	c.Data(http.StatusOK, "application/pdf", buf.Bytes())
}

// ListOrders handles GET /api/orders
// Lists orders based on account type with pagination and optional buyer filtering
// ADMIN: can see all orders, optionally filtered by buyerID query param
// BUYER: only their own orders
// SELLER: only orders where they have items (with only their items shown)
func (h *OrderHandler) ListOrders(c *gin.Context) {
	// Get user info from JWT middleware
	userID, ok := auth.GetUserID(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "user ID not found in token"})
		return
	}

	accountType, ok := auth.GetAccountType(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "account type not found in token"})
		return
	}

	// Parse pagination parameters
	page := 0
	if pageStr := c.Query("page"); pageStr != "" {
		if p, err := strconv.Atoi(pageStr); err == nil && p >= 0 {
			page = p
		}
	}

	pageSize := 20
	if sizeStr := c.Query("page_size"); sizeStr != "" {
		if s, err := strconv.Atoi(sizeStr); err == nil && s > 0 && s <= 100 {
			pageSize = s
		}
	}

	// Parse optional buyer filter (only for ADMIN)
	// Supports both buyer_id (UUID) and buyer_email (email) filtering
	var buyerIDFilter *string
	var buyerEmailFilter *string
	if accountType == auth.AccountTypeAdmin {
		if buyerID := c.Query("buyer_id"); buyerID != "" {
			buyerIDFilter = &buyerID
		}
		if buyerEmail := c.Query("buyer_email"); buyerEmail != "" {
			buyerEmailFilter = &buyerEmail
		}
	}

	// Extract auth token for user service calls (if needed for email filtering)
	authToken := ""
	if authHeader := c.GetHeader("Authorization"); authHeader != "" {
		if strings.HasPrefix(authHeader, "Bearer ") {
			authToken = strings.TrimPrefix(authHeader, "Bearer ")
		}
	}

	response, err := h.orderService.ListOrders(c.Request.Context(), accountType, userID, buyerIDFilter, buyerEmailFilter, authToken, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, response)
}

// CreateFullRefund handles POST /api/orders/:id/refund
// Creates a full refund for an order (admin-only)
func (h *OrderHandler) CreateFullRefund(c *gin.Context) {
	orderIDStr := c.Param("id")
	orderID, err := uuid.Parse(orderIDStr)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid order ID"})
		return
	}

	// Verify admin access
	accountType, ok := auth.GetAccountType(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "account type not found in token"})
		return
	}

	if accountType != auth.AccountTypeAdmin {
		c.JSON(http.StatusForbidden, gin.H{"error": "only admins can create refunds"})
		return
	}

	if err := h.orderService.CreateFullRefund(c.Request.Context(), orderID); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, gin.H{"message": "Full refund created successfully"})
}

// CreatePartialRefund handles POST /api/orders/:id/partial-refund
// Creates a partial refund for an order (admin-only)
func (h *OrderHandler) CreatePartialRefund(c *gin.Context) {
	orderIDStr := c.Param("id")
	orderID, err := uuid.Parse(orderIDStr)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid order ID"})
		return
	}

	// Verify admin access
	accountType, ok := auth.GetAccountType(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "account type not found in token"})
		return
	}

	if accountType != auth.AccountTypeAdmin {
		c.JSON(http.StatusForbidden, gin.H{"error": "only admins can create partial refunds"})
		return
	}

	var req service.PartialRefundRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.orderService.CreatePartialRefund(c.Request.Context(), orderID, req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, gin.H{"message": "Partial refund created successfully"})
}

// GetShipment handles GET /internal/shipments/:id
// Retrieves a shipment by ID (internal API)
func (h *OrderHandler) GetShipment(c *gin.Context) {
	shipmentIDStr := c.Param("id")
	shipmentID, err := uuid.Parse(shipmentIDStr)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid shipment ID"})
		return
	}

	shipment, err := h.orderService.GetShipmentByID(c.Request.Context(), shipmentID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "shipment not found"})
		return
	}

	c.JSON(http.StatusOK, shipment)
}

// GetShipmentsByOrder handles GET /internal/orders/:id/shipments
// Retrieves all shipments for an order (internal API)
// Returns empty array if no shipments exist (order may not have shipments yet)
func (h *OrderHandler) GetShipmentsByOrder(c *gin.Context) {
	orderIDStr := c.Param("id")
	log.Printf("[GetShipmentsByOrder] Received request for order ID: %s", orderIDStr)

	orderID, err := uuid.Parse(orderIDStr)
	if err != nil {
		log.Printf("[GetShipmentsByOrder] Invalid order ID format: %s, error: %v", orderIDStr, err)
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid order ID"})
		return
	}

	log.Printf("[GetShipmentsByOrder] Parsed order ID: %s, querying database...", orderID)

	// Get shipments from repository
	shipments, err := h.orderService.GetShipmentsByOrderID(c.Request.Context(), orderID)
	if err != nil {
		log.Printf("[GetShipmentsByOrder] Database error for order %s: %v", orderID, err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("Failed to get shipments: %v", err)})
		return
	}

	log.Printf("[GetShipmentsByOrder] Found %d shipments for order %s", len(shipments), orderID)

	// Return shipments array (empty if no shipments)
	c.JSON(http.StatusOK, shipments)
}

// UpdateShipmentStatus handles PUT /internal/shipments/:id/status
// Updates the status of a shipment (internal API)
func (h *OrderHandler) UpdateShipmentStatus(c *gin.Context) {
	shipmentIDStr := c.Param("id")
	shipmentID, err := uuid.Parse(shipmentIDStr)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid shipment ID"})
		return
	}

	var req struct {
		Status      string  `json:"status" binding:"required"`
		ShippedAt   *string `json:"shipped_at"`
		DeliveredAt *string `json:"delivered_at"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var shippedAt, deliveredAt *time.Time
	if req.ShippedAt != nil {
		t, err := time.Parse(time.RFC3339, *req.ShippedAt)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid shipped_at format"})
			return
		}
		shippedAt = &t
	}
	if req.DeliveredAt != nil {
		t, err := time.Parse(time.RFC3339, *req.DeliveredAt)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid delivered_at format"})
			return
		}
		deliveredAt = &t
	}

	shipment, err := h.orderService.UpdateShipmentStatus(c.Request.Context(), shipmentID, req.Status, shippedAt, deliveredAt)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, shipment)
}

// UpdateShipmentTracking handles PUT /internal/shipments/:id/tracking
// Updates the tracking information for a shipment (internal API)
func (h *OrderHandler) UpdateShipmentTracking(c *gin.Context) {
	shipmentIDStr := c.Param("id")
	shipmentID, err := uuid.Parse(shipmentIDStr)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid shipment ID"})
		return
	}

	var req struct {
		TrackingNumber string `json:"tracking_number" binding:"required"`
		Carrier        string `json:"carrier" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	shipment, err := h.orderService.UpdateShipmentTracking(c.Request.Context(), shipmentID, req.TrackingNumber, req.Carrier)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, shipment)
}

// GetShipmentsBySeller handles GET /internal/sellers/:sellerId/shipments
// Retrieves all shipments for a seller (internal API)
func (h *OrderHandler) GetShipmentsBySeller(c *gin.Context) {
	sellerID := c.Param("sellerId")
	if sellerID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "seller ID is required"})
		return
	}

	// Parse pagination parameters
	limit := 50
	offset := 0
	if limitStr := c.Query("limit"); limitStr != "" {
		if parsedLimit, err := strconv.Atoi(limitStr); err == nil && parsedLimit > 0 {
			limit = parsedLimit
		}
	}
	if offsetStr := c.Query("offset"); offsetStr != "" {
		if parsedOffset, err := strconv.Atoi(offsetStr); err == nil && parsedOffset >= 0 {
			offset = parsedOffset
		}
	}

	shipments, err := h.orderService.GetShipmentsBySellerID(c.Request.Context(), sellerID, limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	// Always return an array, even if empty (nil becomes empty array)
	if shipments == nil {
		shipments = []models.Shipment{}
	}

	c.JSON(http.StatusOK, shipments)
}

// GetShipmentByTracking handles GET /internal/shipments/by-tracking/:trackingNumber
// Retrieves a shipment by tracking number (internal API)
func (h *OrderHandler) GetShipmentByTracking(c *gin.Context) {
	trackingNumber := c.Param("trackingNumber")
	if trackingNumber == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "tracking number is required"})
		return
	}

	shipment, err := h.orderService.GetShipmentByTrackingNumber(c.Request.Context(), trackingNumber)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "shipment not found"})
		return
	}

	c.JSON(http.StatusOK, shipment)
}

// Health handles GET /health
// Checks if the service is healthy
func (h *OrderHandler) Health(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "healthy"})
}
