# Go Crash Course for Order Service

This document provides a crash course on Go (Golang) and the frameworks/libraries used in the Order Service.

## Table of Contents

1. [Go Basics](#go-basics)
2. [Project Structure](#project-structure)
3. [Gin Web Framework](#gin-web-framework)
4. [Database (PostgreSQL with pgx)](#database-postgresql-with-pgx)
5. [Kafka Integration](#kafka-integration)
6. [PDF Generation](#pdf-generation)
7. [Best Practices](#best-practices)
8. [Common Patterns](#common-patterns)

---

## Go Basics

### Variables and Types

```go
// Variable declaration
var name string = "Order Service"
var port int = 8080

// Short declaration (type inferred)
name := "Order Service"
port := 8080

// Multiple variables
var (
    host string = "localhost"
    port int = 8080
)

// Constants
const (
    DefaultPort = 8080
    ServiceName = "order-service"
)
```

### Pointers

```go
// Go has pointers (like C), but no pointer arithmetic
var x int = 42
var p *int = &x  // p is a pointer to x
*p = 21          // dereference p to change x

// Common pattern: pass by reference to modify
func updateOrder(o *Order) {
    o.Status = "CONFIRMED"
}
```

### Structs

```go
// Define a struct
type Order struct {
    ID        uuid.UUID
    BuyerID   string
    Status    string
    CreatedAt time.Time
}

// Create instance
order := Order{
    ID:        uuid.New(),
    BuyerID:   "buyer-123",
    Status:    "PENDING",
    CreatedAt: time.Now(),
}

// Access fields
fmt.Println(order.ID)
```

### Methods

```go
// Methods are functions with a receiver
func (o *Order) Confirm() {
    o.Status = "CONFIRMED"
    o.ConfirmedAt = time.Now()
}

// Call method
order.Confirm()
```

### Interfaces

```go
// Interface defines behavior (what methods a type must have)
type OrderRepository interface {
    Create(order *Order) error
    GetByID(id uuid.UUID) (*Order, error)
    Update(order *Order) error
}

// Any type that implements these methods satisfies the interface
type PostgresOrderRepository struct {
    db *pgx.Conn
}

func (r *PostgresOrderRepository) Create(order *Order) error {
    // implementation
}
```

### Error Handling

```go
// Go doesn't have exceptions - errors are returned as values
func divide(a, b int) (int, error) {
    if b == 0 {
        return 0, fmt.Errorf("division by zero")
    }
    return a / b, nil
}

// Call and handle error
result, err := divide(10, 2)
if err != nil {
    log.Printf("Error: %v", err)
    return
}
fmt.Println(result)
```

### Slices and Maps

```go
// Slices (dynamic arrays)
items := []OrderItem{}
items = append(items, OrderItem{...})

// Maps (key-value pairs)
orders := make(map[uuid.UUID]*Order)
orders[orderID] = &order

// Check if key exists
if order, exists := orders[orderID]; exists {
    fmt.Println(order)
}
```

### Goroutines and Channels

```go
// Goroutine (lightweight thread)
go processOrder(order)

// Channel (for communication between goroutines)
ch := make(chan Order, 10)  // buffered channel

// Send
ch <- order

// Receive
order := <-ch

// Select (like switch for channels)
select {
case order := <-ch:
    processOrder(order)
case <-time.After(5 * time.Second):
    log.Println("Timeout")
}
```

---

## Project Structure

```
services/order/
├── go.mod              # Go module definition
├── go.sum              # Dependency checksums
├── main.go             # Application entry point
├── Dockerfile          # Container build file
├── README.md           # Service documentation
├── GO_CRASH_COURSE.md  # This file
├── internal/           # Private application code
│   ├── api/            # HTTP handlers (controllers)
│   ├── service/        # Business logic
│   ├── repository/      # Database access
│   ├── kafka/          # Kafka consumer/producer
│   ├── invoice/        # Invoice generation
│   └── models/         # Domain models
├── migrations/         # Database migrations
└── kubernetes/        # K8s manifests
```

**Key Principle**: Code in `internal/` cannot be imported by other Go modules. This enforces encapsulation.

---

## Gin Web Framework

Gin is a fast HTTP web framework for Go.

### Basic Setup

```go
import "github.com/gin-gonic/gin"

func main() {
    // Create router
    r := gin.Default()
    
    // Routes
    r.GET("/health", healthHandler)
    r.POST("/orders", createOrderHandler)
    
    // Start server
    r.Run(":8080")
}
```

### Handlers

```go
// Handler function signature
func createOrderHandler(c *gin.Context) {
    // Parse request body
    var req CreateOrderRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(400, gin.H{"error": err.Error()})
        return
    }
    
    // Business logic
    order, err := orderService.CreateOrder(req)
    if err != nil {
        c.JSON(500, gin.H{"error": err.Error()})
        return
    }
    
    // Response
    c.JSON(201, order)
}
```

### Request Binding

```go
// JSON binding
type CreateOrderRequest struct {
    BuyerID string `json:"buyer_id" binding:"required"`
    Items   []OrderItem `json:"items" binding:"required"`
}

// URL parameters
func getOrderHandler(c *gin.Context) {
    orderID := c.Param("id")
    // ...
}

// Query parameters
func listOrdersHandler(c *gin.Context) {
    status := c.Query("status")
    limit := c.DefaultQuery("limit", "10")
    // ...
}
```

### Middleware

```go
// Custom middleware
func authMiddleware() gin.HandlerFunc {
    return func(c *gin.Context) {
        token := c.GetHeader("Authorization")
        if token == "" {
            c.JSON(401, gin.H{"error": "unauthorized"})
            c.Abort()
            return
        }
        // Validate token...
        c.Next()
    }
}

// Use middleware
r.Use(authMiddleware())
r.GET("/orders", getOrdersHandler)
```

---

## Database (PostgreSQL with pgx)

pgx is a PostgreSQL driver for Go.

### Connection

```go
import "github.com/jackc/pgx/v5/pgxpool"

// Create connection pool
config, err := pgxpool.ParseConfig("postgres://user:pass@localhost/dbname")
if err != nil {
    log.Fatal(err)
}

pool, err := pgxpool.NewWithConfig(context.Background(), config)
if err != nil {
    log.Fatal(err)
}
defer pool.Close()
```

### Queries

```go
// Query single row
var order Order
err := pool.QueryRow(context.Background(),
    "SELECT id, buyer_id, status FROM orders WHERE id = $1",
    orderID,
).Scan(&order.ID, &order.BuyerID, &order.Status)

// Query multiple rows
rows, err := pool.Query(context.Background(),
    "SELECT id, buyer_id, status FROM orders WHERE status = $1",
    "PENDING",
)
defer rows.Close()

for rows.Next() {
    var order Order
    err := rows.Scan(&order.ID, &order.BuyerID, &order.Status)
    // ...
}
```

### Transactions

```go
tx, err := pool.Begin(context.Background())
if err != nil {
    return err
}
defer tx.Rollback(context.Background())

// Execute queries in transaction
_, err = tx.Exec(context.Background(),
    "INSERT INTO orders (id, buyer_id) VALUES ($1, $2)",
    orderID, buyerID,
)

// Commit
if err := tx.Commit(context.Background()); err != nil {
    return err
}
```

### Repository Pattern

```go
type OrderRepository struct {
    pool *pgxpool.Pool
}

func (r *OrderRepository) Create(ctx context.Context, order *Order) error {
    _, err := r.pool.Exec(ctx,
        `INSERT INTO orders (id, buyer_id, status, created_at)
         VALUES ($1, $2, $3, $4)`,
        order.ID, order.BuyerID, order.Status, order.CreatedAt,
    )
    return err
}
```

---

## Kafka Integration

We use `segmentio/kafka-go` for Kafka.

### Consumer

```go
import "github.com/segmentio/kafka-go"

// Create consumer
r := kafka.NewReader(kafka.ReaderConfig{
    Brokers:  []string{"localhost:9092"},
    Topic:    "payment.events",
    GroupID:  "order-service",
    MinBytes: 10e3, // 10KB
    MaxBytes: 10e6, // 10MB
})
defer r.Close()

// Read messages
for {
    msg, err := r.ReadMessage(context.Background())
    if err != nil {
        log.Printf("Error reading message: %v", err)
        continue
    }
    
    // Process message
    processPaymentEvent(msg.Value)
}
```

### Producer

```go
w := kafka.NewWriter(kafka.WriterConfig{
    Brokers: []string{"localhost:9092"},
    Topic:   "order.events",
})

defer w.Close()

// Write message
err := w.WriteMessages(context.Background(),
    kafka.Message{
        Key:   []byte(orderID),
        Value: eventJSON,
    },
)
```

---

## PDF Generation

We use `jung-kurt/gofpdf` for PDF generation.

### Basic PDF

```go
import "github.com/jung-kurt/gofpdf"

pdf := gofpdf.New("P", "mm", "A4", "")
pdf.AddPage()

// Set font
pdf.SetFont("Arial", "B", 16)

// Add text
pdf.Cell(40, 10, "Invoice")

// Add line
pdf.Ln(10)

// Output
pdf.OutputFileAndClose("invoice.pdf")
```

### Invoice Example

```go
func generateInvoice(order *Order) (*bytes.Buffer, error) {
    pdf := gofpdf.New("P", "mm", "A4", "")
    pdf.AddPage()
    
    // Header
    pdf.SetFont("Arial", "B", 20)
    pdf.Cell(0, 10, "INVOICE")
    pdf.Ln(10)
    
    // Order details
    pdf.SetFont("Arial", "", 12)
    pdf.Cell(0, 10, fmt.Sprintf("Order ID: %s", order.ID))
    pdf.Ln(5)
    pdf.Cell(0, 10, fmt.Sprintf("Date: %s", order.CreatedAt.Format("2006-01-02")))
    pdf.Ln(10)
    
    // Items table
    // ... (see invoice package for full implementation)
    
    var buf bytes.Buffer
    err := pdf.Output(&buf)
    return &buf, err
}
```

---

## Best Practices

### 1. Error Handling

```go
// Always check errors
result, err := doSomething()
if err != nil {
    return fmt.Errorf("failed to do something: %w", err)  // Wrap error
}

// Use errors.Is() and errors.As() for error checking
if errors.Is(err, sql.ErrNoRows) {
    return nil, ErrOrderNotFound
}
```

### 2. Context Usage

```go
// Always pass context for cancellation/timeout
func (r *Repository) GetOrder(ctx context.Context, id uuid.UUID) (*Order, error) {
    // Context is used for cancellation
    row := r.pool.QueryRow(ctx, "SELECT ...", id)
    // ...
}

// Create context with timeout
ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
defer cancel()
```

### 3. Dependency Injection

```go
// Pass dependencies via constructor
type OrderService struct {
    repo   OrderRepository
    kafka  KafkaProducer
    invoice InvoiceGenerator
}

func NewOrderService(repo OrderRepository, kafka KafkaProducer, invoice InvoiceGenerator) *OrderService {
    return &OrderService{
        repo:    repo,
        kafka:   kafka,
        invoice: invoice,
    }
}
```

### 4. Interface Segregation

```go
// Small, focused interfaces
type OrderReader interface {
    GetByID(id uuid.UUID) (*Order, error)
}

type OrderWriter interface {
    Create(order *Order) error
    Update(order *Order) error
}

// Compose if needed
type OrderRepository interface {
    OrderReader
    OrderWriter
}
```

### 5. Naming Conventions

```go
// Exported (public): Capital letter
type Order struct { ... }
func CreateOrder() { ... }

// Unexported (private): lowercase
type orderService struct { ... }
func validateOrder() { ... }

// Constants: Uppercase
const DefaultPort = 8080

// Error variables: Err prefix
var ErrOrderNotFound = errors.New("order not found")
```

---

## Common Patterns

### 1. Service Layer Pattern

```go
type OrderService struct {
    repo OrderRepository
}

func (s *OrderService) CreateOrder(req CreateOrderRequest) (*Order, error) {
    // Validation
    if req.BuyerID == "" {
        return nil, ErrInvalidRequest
    }
    
    // Business logic
    order := &Order{
        ID:      uuid.New(),
        BuyerID: req.BuyerID,
        Status:  "PENDING",
    }
    
    // Persist
    if err := s.repo.Create(order); err != nil {
        return nil, err
    }
    
    return order, nil
}
```

### 2. Repository Pattern

```go
type PostgresOrderRepository struct {
    pool *pgxpool.Pool
}

func (r *PostgresOrderRepository) Create(ctx context.Context, order *Order) error {
    _, err := r.pool.Exec(ctx,
        `INSERT INTO orders (id, buyer_id, status) VALUES ($1, $2, $3)`,
        order.ID, order.BuyerID, order.Status,
    )
    return err
}
```

### 3. Handler Pattern

```go
type OrderHandler struct {
    service OrderService
}

func (h *OrderHandler) CreateOrder(c *gin.Context) {
    var req CreateOrderRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(400, gin.H{"error": err.Error()})
        return
    }
    
    order, err := h.service.CreateOrder(req)
    if err != nil {
        c.JSON(500, gin.H{"error": err.Error()})
        return
    }
    
    c.JSON(201, order)
}
```

---

## Key Takeaways

1. **Go is simple**: No classes, inheritance, or exceptions
2. **Explicit is better**: Errors are values, not exceptions
3. **Interfaces are implicit**: If a type has the methods, it implements the interface
4. **Concurrency is built-in**: Goroutines and channels make concurrency easy
5. **Composition over inheritance**: Use struct embedding and interfaces
6. **Context for cancellation**: Always use context for async operations

---

## Resources

- [Go Tour](https://go.dev/tour/) - Interactive Go tutorial
- [Effective Go](https://go.dev/doc/effective_go) - Go best practices
- [Gin Documentation](https://gin-gonic.com/docs/)
- [pgx Documentation](https://pkg.go.dev/github.com/jackc/pgx/v5)
- [Kafka Go Documentation](https://pkg.go.dev/github.com/segmentio/kafka-go)
