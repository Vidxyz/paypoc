package api

import (
	"fmt"
	"net/http"
	"strconv"

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
	buf, err := h.invoiceGen.GenerateInvoice(order.Order, order.Items)
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
	var buyerIDFilter *string
	if accountType == auth.AccountTypeAdmin {
		if buyerID := c.Query("buyer_id"); buyerID != "" {
			buyerIDFilter = &buyerID
		}
	}

	response, err := h.orderService.ListOrders(c.Request.Context(), accountType, userID, buyerIDFilter, page, pageSize)
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

// Health handles GET /health
// Checks if the service is healthy
func (h *OrderHandler) Health(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "healthy"})
}
