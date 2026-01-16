# Order Service

The Order Service manages the order lifecycle from checkout to delivery. It orchestrates checkout, handles inventory allocation, creates payments, and generates invoices.

## Features

- **Checkout Orchestration**: Creates provisional orders from cart data
- **Inventory Management**: Converts soft reservations to hard allocations on checkout
- **Payment Integration**: Creates payments via Payments Service
- **Order Confirmation**: Consumes PaymentCapturedEvent to confirm orders
- **Invoice Generation**: Generates PDF invoices on-demand for confirmed orders
- **Multi-Seller Support**: Creates separate shipments for each seller

## Architecture

```
Cart Service → Order Service (create provisional order)
Order Service → Inventory Service (hard allocation)
Order Service → Payments Service (create payment)
Payments Service → Kafka (PaymentCapturedEvent)
Kafka → Order Service (confirm order)
Order Service → Inventory Service (confirm sale)
Order Service → Kafka (OrderConfirmedEvent)
```

## API Endpoints

### Internal API (Service-to-Service)

- `POST /internal/orders` - Create provisional order from cart

### Public API

- `GET /api/orders/:id` - Get order details
- `GET /api/orders/:id/invoice` - Download invoice PDF (buyer only)

### Health

- `GET /health` - Health check

## Environment Variables

- `PORT` - Server port (default: 8084)
- `DATABASE_URL` - PostgreSQL connection string
- `INVENTORY_SERVICE_URL` - Inventory service URL
- `INVENTORY_INTERNAL_API_TOKEN` - Token for inventory service internal API
- `PAYMENT_SERVICE_URL` - Payments service URL
- `CART_SERVICE_URL` - Cart service URL
- `KAFKA_BOOTSTRAP_SERVERS` - Kafka broker addresses
- `KAFKA_EVENTS_TOPIC` - Kafka topic for events (default: payment.events)
- `KAFKA_CONSUMER_GROUP_ID` - Kafka consumer group ID (default: order-service)

## Database

PostgreSQL database with the following tables:
- `orders` - Order records
- `order_items` - Items in orders
- `shipments` - Shipments (one per seller per order)

## Invoice Generation

Invoices are generated on-demand as PDFs when requested via `GET /api/orders/:id/invoice`. Only confirmed orders can have invoices generated.

## Development

See `GO_CRASH_COURSE.md` for a comprehensive guide to Go and the frameworks used in this service.
