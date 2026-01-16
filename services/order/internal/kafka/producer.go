package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"log"

	"github.com/payments-platform/order-service/internal/models"
	"github.com/segmentio/kafka-go"
)

type KafkaProducer struct {
	writer *kafka.Writer
}

func NewKafkaProducer(brokers []string, topic string) *KafkaProducer {
	return &KafkaProducer{
		writer: &kafka.Writer{
			Addr:     kafka.TCP(brokers...),
			Topic:    topic,
			Balancer: &kafka.LeastBytes{},
		},
	}
}

func (p *KafkaProducer) Close() error {
	return p.writer.Close()
}

func (p *KafkaProducer) PublishOrderConfirmedEvent(ctx context.Context, event models.OrderConfirmedEvent) error {
	eventJSON, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("failed to marshal event: %w", err)
	}

	msg := kafka.Message{
		Key:   []byte(event.OrderID.String()),
		Value: eventJSON,
	}

	if err := p.writer.WriteMessages(ctx, msg); err != nil {
		return fmt.Errorf("failed to write message: %w", err)
	}

	log.Printf("Published OrderConfirmedEvent for order %s", event.OrderID)
	return nil
}

// KafkaProducer interface for dependency injection
type Producer interface {
	PublishOrderConfirmedEvent(ctx context.Context, event models.OrderConfirmedEvent) error
	Close() error
}
