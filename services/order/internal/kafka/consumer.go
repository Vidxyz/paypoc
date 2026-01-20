package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"time"

	"github.com/payments-platform/order-service/internal/models"
	"github.com/payments-platform/order-service/internal/service"
	"github.com/segmentio/kafka-go"
)

type KafkaConsumer struct {
	reader       *kafka.Reader
	orderService *service.OrderService
}

func NewKafkaConsumer(brokers []string, topic, groupID string, orderService *service.OrderService) *KafkaConsumer {
	return &KafkaConsumer{
		reader: kafka.NewReader(kafka.ReaderConfig{
			Brokers:  brokers,
			Topic:    topic,
			GroupID:  groupID,
			MinBytes: 10e3, // 10KB
			MaxBytes: 10e6, // 10MB
		}),
		orderService: orderService,
	}
}

func (c *KafkaConsumer) Start(ctx context.Context) error {
	log.Printf("Starting Kafka consumer for topic: %s, group: %s", c.reader.Config().Topic, c.reader.Config().GroupID)

	for {
		select {
		case <-ctx.Done():
			log.Println("Kafka consumer context cancelled, stopping...")
			return ctx.Err()
		default:
			msg, err := c.reader.ReadMessage(ctx)
			if err != nil {
				log.Printf("Error reading message: %v", err)
				time.Sleep(1 * time.Second)
				continue
			}

			if err := c.processMessage(ctx, msg.Value); err != nil {
				log.Printf("Error processing message: %v", err)
				// Continue processing other messages
			}
		}
	}
}

func (c *KafkaConsumer) processMessage(ctx context.Context, data []byte) error {
	// Parse event type first
	var eventType struct {
		Type string `json:"type"`
	}
	if err := json.Unmarshal(data, &eventType); err != nil {
		return fmt.Errorf("failed to parse event type: %w", err)
	}

	// Route to appropriate handler based on event type
	switch eventType.Type {
	case "PAYMENT_CAPTURED":
		return c.handlePaymentCaptured(ctx, data)
	case "REFUND_COMPLETED":
		return c.handleRefundCompleted(ctx, data)
	case "PAYMENT_FAILED":
		return c.handlePaymentFailed(ctx, data)
	default:
		log.Printf("Skipping event type: %s", eventType.Type)
		return nil
	}
}

func (c *KafkaConsumer) handlePaymentCaptured(ctx context.Context, data []byte) error {
	// Parse PaymentCapturedEvent
	var event models.PaymentCapturedEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return fmt.Errorf("failed to parse PaymentCapturedEvent: %w", err)
	}

	log.Printf("Processing PaymentCapturedEvent for payment %s", event.PaymentID)

	// Confirm order
	if err := c.orderService.ConfirmOrder(ctx, event.PaymentID); err != nil {
		return fmt.Errorf("failed to confirm order: %w", err)
	}

	log.Printf("Successfully confirmed order for payment %s", event.PaymentID)
	return nil
}

func (c *KafkaConsumer) handleRefundCompleted(ctx context.Context, data []byte) error {
	// Parse RefundCompletedEvent
	var event models.RefundCompletedEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return fmt.Errorf("failed to parse RefundCompletedEvent: %w", err)
	}

	log.Printf("Processing RefundCompletedEvent for refund %s (order: %s)", event.RefundID, event.OrderID)

	// Process refund
	if err := c.orderService.ProcessRefund(ctx, event); err != nil {
		return fmt.Errorf("failed to process refund: %w", err)
	}

	log.Printf("Successfully processed refund %s for order %s", event.RefundID, event.OrderID)
	return nil
}

func (c *KafkaConsumer) handlePaymentFailed(ctx context.Context, data []byte) error {
	// Parse PaymentFailedEvent
	var event models.PaymentFailedEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return fmt.Errorf("failed to parse PaymentFailedEvent: %w", err)
	}

	log.Printf("Processing PaymentFailedEvent for payment %s (reason: %s)", event.PaymentID, event.Reason)

	// Get order by payment ID
	order, err := c.orderService.GetOrderByPaymentID(ctx, event.PaymentID)
	if err != nil {
		log.Printf("Warning: failed to get order for payment %s: %v", event.PaymentID, err)
		return nil // Don't fail - order might not exist or payment might be for a different system
	}

	// Mark cart as ABANDONED if cart_id exists
	if order.CartID != nil {
		if err := c.orderService.MarkCartAbandoned(ctx, *order.CartID, fmt.Sprintf("Payment failed: %s", event.Reason)); err != nil {
			log.Printf("Warning: failed to mark cart %s as ABANDONED: %v", *order.CartID, err)
			// Don't fail - cleanup job will catch it later
		} else {
			log.Printf("Successfully marked cart %s as ABANDONED due to payment failure", *order.CartID)
		}
	}

	return nil
}

func (c *KafkaConsumer) Close() error {
	return c.reader.Close()
}
