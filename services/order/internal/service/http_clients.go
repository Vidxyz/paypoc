package service

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/google/uuid"
)

// InventoryClient handles communication with inventory service
type InventoryClient struct {
	baseURL string
	token   string
	client  *http.Client
}

func NewInventoryClient(baseURL, token string) *InventoryClient {
	return &InventoryClient{
		baseURL: baseURL,
		token:   token,
		client: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

// CreateHardAllocation converts a soft reservation to a hard allocation
func (c *InventoryClient) CreateHardAllocation(ctx context.Context, reservationID, orderID uuid.UUID, quantity int) error {
	// Request body with orderId
	requestBody := map[string]string{
		"orderId": orderID.String(),
	}
	jsonData, err := json.Marshal(requestBody)
	if err != nil {
		return err
	}

	req, err := http.NewRequestWithContext(ctx, "POST",
		fmt.Sprintf("%s/internal/reservations/%s/allocate", c.baseURL, reservationID), bytes.NewBuffer(jsonData))
	if err != nil {
		return err
	}

	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.client.Do(req)
	if err != nil {
		return fmt.Errorf("inventory service endpoint not available: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("inventory service returned %d: %s", resp.StatusCode, string(body))
	}

	return nil
}

func (c *InventoryClient) ConfirmSale(ctx context.Context, reservationID uuid.UUID) error {
	req, err := http.NewRequestWithContext(ctx, "POST",
		fmt.Sprintf("%s/internal/reservations/%s/confirm-sale", c.baseURL, reservationID), nil)
	if err != nil {
		return err
	}

	req.Header.Set("Authorization", "Bearer "+c.token)

	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("inventory service returned %d: %s", resp.StatusCode, string(body))
	}

	return nil
}

func (c *InventoryClient) ReleaseReservation(ctx context.Context, reservationID uuid.UUID) error {
	req, err := http.NewRequestWithContext(ctx, "POST",
		fmt.Sprintf("%s/internal/reservations/%s/release", c.baseURL, reservationID), nil)
	if err != nil {
		return err
	}

	req.Header.Set("Authorization", "Bearer "+c.token)

	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("inventory service returned %d: %s", resp.StatusCode, string(body))
	}

	return nil
}

// PaymentClient handles communication with payments service
type PaymentClient struct {
	baseURL       string
	internalToken string
	client        *http.Client
}

func NewPaymentClient(baseURL, internalToken string) *PaymentClient {
	return &PaymentClient{
		baseURL:       baseURL,
		internalToken: internalToken,
		client: &http.Client{
			Timeout: 30 * time.Second, // Increased timeout for payment creation which may involve Stripe API calls
		},
	}
}

type CreatePaymentResponse struct {
	PaymentID    uuid.UUID `json:"id"`
	ClientSecret string    `json:"clientSecret"`
}

// CreateOrderPaymentRequest represents a request to create an order payment (one payment per order, multiple sellers)
type CreateOrderPaymentRequest struct {
	OrderID          uuid.UUID                `json:"orderId"`
	BuyerID          string                   `json:"buyerId"`
	GrossAmountCents int64                    `json:"grossAmountCents"`
	Currency         string                   `json:"currency"`
	SellerBreakdown  []SellerPaymentBreakdown `json:"sellerBreakdown"`
	Description      string                   `json:"description,omitempty"`
}

// SellerPaymentBreakdown represents one seller's portion of the order
type SellerPaymentBreakdown struct {
	SellerID               string `json:"sellerId"`
	SellerGrossAmountCents int64  `json:"sellerGrossAmountCents"`
}

func (c *PaymentClient) CreateOrderPayment(ctx context.Context, req CreateOrderPaymentRequest) (*CreatePaymentResponse, error) {
	jsonData, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}

	// Use internal API endpoint
	httpReq, err := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/internal/payments/order", bytes.NewBuffer(jsonData))
	if err != nil {
		return nil, err
	}

	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Authorization", "Bearer "+c.internalToken)

	resp, err := c.client.Do(httpReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusCreated {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("payments service returned %d: %s", resp.StatusCode, string(body))
	}

	// PaymentResponseDto structure from payments service
	var paymentResp struct {
		ID           *uuid.UUID `json:"id"`
		ClientSecret string     `json:"clientSecret"`
		Error        string     `json:"error,omitempty"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&paymentResp); err != nil {
		return nil, err
	}

	if paymentResp.Error != "" {
		return nil, fmt.Errorf("payment creation failed: %s", paymentResp.Error)
	}

	if paymentResp.ID == nil {
		return nil, fmt.Errorf("payment response missing id")
	}

	return &CreatePaymentResponse{
		PaymentID:    *paymentResp.ID,
		ClientSecret: paymentResp.ClientSecret,
	}, nil
}

// CartClient handles communication with cart service
type CartClient struct {
	baseURL string
	client  *http.Client
}

func NewCartClient(baseURL string) *CartClient {
	return &CartClient{
		baseURL: baseURL,
		client: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

func (c *CartClient) UpdateCartStatus(ctx context.Context, buyerID, status string) error {
	// This would call the cart service to update cart status
	// For now, we'll just log it
	// In production, you'd make an HTTP call
	return nil
}
