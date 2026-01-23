package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"time"

	"github.com/payments-platform/order-service/internal/service"
	"github.com/segmentio/kafka-go"
)

type CommandsConsumer struct {
	reader       *kafka.Reader
	orderService *service.OrderService
}

func NewCommandsConsumer(brokers []string, topic, groupID string, orderService *service.OrderService) *CommandsConsumer {
	return &CommandsConsumer{
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

func (c *CommandsConsumer) Start(ctx context.Context) error {
	log.Printf("Starting Kafka commands consumer for topic: %s, group: %s", c.reader.Config().Topic, c.reader.Config().GroupID)

	for {
		select {
		case <-ctx.Done():
			log.Println("Kafka commands consumer context cancelled, stopping...")
			return ctx.Err()
		default:
			msg, err := c.reader.ReadMessage(ctx)
			if err != nil {
				log.Printf("Error reading command message: %v", err)
				time.Sleep(1 * time.Second)
				continue
			}

			if err := c.processCommand(ctx, msg.Value); err != nil {
				log.Printf("Error processing command: %v", err)
				// Continue processing other commands
			}
		}
	}
}

func (c *CommandsConsumer) processCommand(ctx context.Context, data []byte) error {
	// Parse command type first
	var commandType struct {
		Type string `json:"type"`
	}
	if err := json.Unmarshal(data, &commandType); err != nil {
		return fmt.Errorf("failed to parse command type: %w", err)
	}

	// Route to appropriate handler based on command type
	switch commandType.Type {
	case "CLEANUP_STALE_ORDERS":
		return c.handleCleanupStaleOrders(ctx, data)
	default:
		log.Printf("Skipping unknown command type: %s", commandType.Type)
		return nil
	}
}

func (c *CommandsConsumer) handleCleanupStaleOrders(ctx context.Context, data []byte) error {
	// Parse cleanup command
	var command struct {
		Type      string `json:"type"`
		Timestamp string `json:"timestamp,omitempty"`
		Timeout   *int   `json:"timeout,omitempty"` // Timeout in minutes, optional
	}
	if err := json.Unmarshal(data, &command); err != nil {
		return fmt.Errorf("failed to parse cleanup command: %w", err)
	}

	// Default timeout: 30 minutes (same as cart cleanup)
	timeoutMinutes := 30
	if command.Timeout != nil {
		timeoutMinutes = *command.Timeout
	}

	log.Printf("=== Received CLEANUP_STALE_ORDERS command ===")
	log.Printf("Timeout: %d minutes", timeoutMinutes)

	result, err := c.orderService.CleanupStalePendingOrders(ctx, timeoutMinutes)
	if err != nil {
		log.Printf("Error during cleanup: %v", err)
		return err
	}

	log.Printf("=== Cleanup completed ===")
	log.Printf("Found: %d orders", result.FoundCount)
	log.Printf("Processed: %d orders", result.ProcessedCount)
	log.Printf("Errors: %d", result.ErrorCount)

	return nil
}

func (c *CommandsConsumer) Close() error {
	return c.reader.Close()
}
