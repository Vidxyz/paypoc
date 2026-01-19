package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-contrib/cors"
	"github.com/gin-gonic/gin"
	"github.com/golang-migrate/migrate/v4"
	_ "github.com/golang-migrate/migrate/v4/database/postgres"
	_ "github.com/golang-migrate/migrate/v4/source/file"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/payments-platform/order-service/internal/api"
	"github.com/payments-platform/order-service/internal/auth"
	"github.com/payments-platform/order-service/internal/invoice"
	"github.com/payments-platform/order-service/internal/kafka"
	"github.com/payments-platform/order-service/internal/repository"
	"github.com/payments-platform/order-service/internal/service"
)

func main() {
	// Load configuration from environment
	config := loadConfig()

	// Initialize database
	dbPool, err := pgxpool.New(context.Background(), config.DatabaseURL)
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}
	defer dbPool.Close()

	// Run migrations
	if err := runMigrations(config.DatabaseURL); err != nil {
		log.Fatalf("Failed to run migrations: %v", err)
	}

	// Initialize repositories
	orderRepo := repository.NewOrderRepository(dbPool)

	// Initialize HTTP clients
	inventoryClient := service.NewInventoryClient(config.InventoryServiceURL, config.InventoryInternalToken)
	paymentClient := service.NewPaymentClient(config.PaymentServiceURL, config.PaymentInternalToken)
	cartClient := service.NewCartClient(config.CartServiceURL)

	// Initialize Kafka producer
	kafkaProducer := kafka.NewKafkaProducer(config.KafkaBrokers, config.KafkaEventsTopic)
	defer kafkaProducer.Close()

	// Initialize services
	orderService := service.NewOrderService(
		orderRepo,
		inventoryClient,
		paymentClient,
		cartClient,
		kafkaProducer,
	)

	// Initialize invoice generator
	invoiceGen := invoice.NewInvoiceGenerator()

	// Initialize handlers
	orderHandler := api.NewOrderHandler(orderService, invoiceGen)

	// Initialize Kafka consumer
	kafkaConsumer := kafka.NewKafkaConsumer(
		config.KafkaBrokers,
		config.KafkaEventsTopic,
		config.KafkaConsumerGroupID,
		orderService,
	)
	defer kafkaConsumer.Close()

	// Start Kafka consumer in background
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() {
		if err := kafkaConsumer.Start(ctx); err != nil {
			log.Printf("Kafka consumer error: %v", err)
		}
	}()

	// Setup HTTP router
	router := setupRouter(orderHandler, config)

	// Start HTTP server
	srv := &http.Server{
		Addr:    ":" + config.Port,
		Handler: router,
	}

	// Graceful shutdown
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()

	log.Printf("Order Service started on port %s", config.Port)

	// Wait for interrupt signal
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("Shutting down server...")

	// Cancel Kafka consumer context
	cancel()

	// Graceful shutdown with timeout
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()

	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("Server forced to shutdown: %v", err)
	}

	log.Println("Server exited")
}

func setupRouter(orderHandler *api.OrderHandler, config *Config) *gin.Engine {
	router := gin.Default()

	// CORS configuration - allow admin console, seller console, and frontend
	corsConfig := cors.Config{
		AllowOrigins:     []string{"https://admin.local", "http://admin.local", "https://seller.local", "http://seller.local", "https://buyit.local", "http://buyit.local"},
		AllowMethods:     []string{"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"},
		AllowHeaders:     []string{"Origin", "Content-Type", "Accept", "Authorization", "X-Requested-With", "Access-Control-Request-Method", "Access-Control-Request-Headers"},
		ExposeHeaders:    []string{"Content-Length", "Content-Type"},
		AllowCredentials: true,
		MaxAge:           3600,
	}
	router.Use(cors.New(corsConfig))

	// Health check
	router.GET("/health", orderHandler.Health)

	// API Documentation
	router.GET("/api-docs", orderHandler.SwaggerUI)
	router.GET("/api-docs/openapi.json", orderHandler.OpenAPIJSON)

	// Initialize JWT authenticator
	jwtAuth := auth.NewJWTAuth(config.Auth0Domain, config.Auth0Audience)

	// Internal API (for service-to-service communication)
	internal := router.Group("/internal")
	{
		// Secure with internal API token
		internal.POST("/orders", auth.InternalTokenAuth(config.InternalAPIToken), orderHandler.CreateOrder)
	}

	// Public API (requires JWT authentication)
	api := router.Group("/api")
	{
		// List orders - supports ADMIN, BUYER, SELLER
		api.GET("/orders", jwtAuth.RequireAccountType(auth.AccountTypeBuyer, auth.AccountTypeAdmin, auth.AccountTypeSeller), orderHandler.ListOrders)
		// Get single order - supports BUYER, ADMIN, and SELLER (sellers see only their items)
		api.GET("/orders/:id", jwtAuth.RequireAccountType(auth.AccountTypeBuyer, auth.AccountTypeAdmin, auth.AccountTypeSeller), orderHandler.GetOrder)
		api.GET("/orders/:id/invoice", jwtAuth.RequireAccountType(auth.AccountTypeBuyer, auth.AccountTypeAdmin), orderHandler.GetInvoice)
		// Full refund - admin only
		api.POST("/orders/:id/refund", jwtAuth.RequireAccountType(auth.AccountTypeAdmin), orderHandler.CreateFullRefund)
		// Partial refund - admin only
		api.POST("/orders/:id/partial-refund", jwtAuth.RequireAccountType(auth.AccountTypeAdmin), orderHandler.CreatePartialRefund)
	}

	return router
}

type Config struct {
	Port                   string
	DatabaseURL            string
	InventoryServiceURL    string
	InventoryInternalToken string
	PaymentServiceURL      string
	PaymentInternalToken   string
	CartServiceURL         string
	KafkaBrokers           []string
	KafkaEventsTopic       string
	KafkaConsumerGroupID   string
	InternalAPIToken       string
	Auth0Domain            string
	Auth0Audience          string
}

func loadConfig() *Config {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8084"
	}

	databaseURL := os.Getenv("DATABASE_URL")
	if databaseURL == "" {
		databaseURL = "postgres://order_user:order_pass@localhost:5432/order_db?sslmode=disable"
	}

	return &Config{
		Port:                   port,
		DatabaseURL:            databaseURL,
		InventoryServiceURL:    getEnv("INVENTORY_SERVICE_URL", "http://inventory-service.inventory.svc.cluster.local:8083"),
		InventoryInternalToken: getEnv("INVENTORY_INTERNAL_API_TOKEN", ""),
		PaymentServiceURL:      getEnv("PAYMENT_SERVICE_URL", "http://payments-service.payments-platform.svc.cluster.local:8080"),
		PaymentInternalToken:   getEnv("PAYMENT_INTERNAL_API_TOKEN", ""),
		CartServiceURL:         getEnv("CART_SERVICE_URL", "http://cart-service.cart.svc.cluster.local:9000"),
		KafkaBrokers:           []string{getEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka-cluster-kafka-bootstrap.kafka.svc.cluster.local:9092")},
		KafkaEventsTopic:       getEnv("KAFKA_EVENTS_TOPIC", "payment.events"),
		KafkaConsumerGroupID:   getEnv("KAFKA_CONSUMER_GROUP_ID", "order-service"),
		InternalAPIToken:       getEnv("INTERNAL_API_TOKEN", ""),
		Auth0Domain:            getEnv("AUTH0_DOMAIN", ""),
		Auth0Audience:          getEnv("AUTH0_AUDIENCE", ""),
	}
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func runMigrations(databaseURL string) error {
	// Create migrate instance
	// Note: golang-migrate uses lib/pq which defaults to sslmode=require
	// For internal Kubernetes connections, DATABASE_URL should include ?sslmode=disable
	m, err := migrate.New(
		"file://migrations",
		databaseURL,
	)
	if err != nil {
		return fmt.Errorf("failed to create migrate instance: %w", err)
	}
	defer m.Close()

	// Run all pending migrations
	if err := m.Up(); err != nil {
		// ErrNoChange means no migrations to run, which is fine
		if err == migrate.ErrNoChange {
			log.Println("No new migrations to run")
			return nil
		}
		return fmt.Errorf("failed to run migrations: %w", err)
	}

	log.Println("Migrations completed successfully")
	return nil
}
