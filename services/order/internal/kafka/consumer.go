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

	// Only process PaymentCapturedEvent
	if eventType.Type != "PAYMENT_CAPTURED" {
		log.Printf("Skipping event type: %s", eventType.Type)
		return nil
	}

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

func (c *KafkaConsumer) Close() error {
	return c.reader.Close()
}
