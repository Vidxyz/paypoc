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
// TODO: This requires an internal endpoint in inventory service that takes reservationId
// For now, this is a placeholder that will need the inventory service to expose:
// POST /internal/reservations/{reservationId}/allocate
// OR we need to look up the reservation first to get inventoryId, then call allocate
func (c *InventoryClient) CreateHardAllocation(ctx context.Context, reservationID, orderID uuid.UUID, quantity int) error {
	// Option 1: If inventory service exposes POST /internal/reservations/{reservationId}/allocate
	req, err := http.NewRequestWithContext(ctx, "POST",
		fmt.Sprintf("%s/internal/reservations/%s/allocate", c.baseURL, reservationID), nil)
	if err != nil {
		return err
	}

	req.Header.Set("Authorization", "Bearer "+c.token)

	resp, err := c.client.Do(req)
	if err != nil {
		// If endpoint doesn't exist, try Option 2: Look up reservation first
		// For now, we'll need to implement this in inventory service
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
	baseURL string
	client  *http.Client
}

func NewPaymentClient(baseURL string) *PaymentClient {
	return &PaymentClient{
		baseURL: baseURL,
		client: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

type CreatePaymentResponse struct {
	PaymentID    uuid.UUID `json:"id"`
	ClientSecret string    `json:"clientSecret"`
}

func (c *PaymentClient) CreatePayment(ctx context.Context, req CreatePaymentRequest) (*CreatePaymentResponse, error) {
	jsonData, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}

	httpReq, err := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/api/payments", bytes.NewBuffer(jsonData))
	if err != nil {
		return nil, err
	}

	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := c.client.Do(httpReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusCreated {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("payments service returned %d: %s", resp.StatusCode, string(body))
	}

	var paymentResp struct {
		Payment struct {
			ID uuid.UUID `json:"id"`
		} `json:"payment"`
		ClientSecret string `json:"clientSecret"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&paymentResp); err != nil {
		return nil, err
	}

	return &CreatePaymentResponse{
		PaymentID:    paymentResp.Payment.ID,
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
