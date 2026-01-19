# Order Service

The Order Service manages the order lifecycle from checkout to delivery. It orchestrates checkout, handles inventory allocation, creates payments, and generates invoices.

## Features

- **Checkout Orchestration**: Creates provisional orders from cart data
- **Inventory Management**: Converts soft reservations to hard allocations on checkout
- **Payment Integration**: Creates payments via Payments Service
- **Order Confirmation**: Consumes PaymentCapturedEvent to confirm orders
- **Invoice Generation**: Generates PDF invoices on-demand for confirmed orders
- **Multi-Seller Support**: Creates separate shipments for each seller
- **Refund Tracking**: Tracks partial and full refunds at the order item level with full audit trail

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
- `orders` - Order records (includes `refund_status`: NONE, PARTIAL, FULL)
- `order_items` - Items in orders (includes `refunded_quantity` for quick state queries)
- `shipments` - Shipments (one per seller per order)
- `refunded_order_items` - Audit trail of refunded items (tracks each refund event)

## Invoice Generation

Invoices are generated on-demand as PDFs when requested via `GET /api/orders/:id/invoice`. Only confirmed orders can have invoices generated.

## Refund Flow

The Order Service tracks refunds at the order item level, supporting both partial item refunds and full order refunds. All refund operations are processed atomically within database transactions.

### Refund Types

#### Partial Refund
Refunds specific quantities of specific order items. For example:
- Order has: Item A (qty: 5), Item B (qty: 3)
- Partial refund: Item A (qty: 2), Item B (qty: 1)
- Result: Item A (refunded: 2, remaining: 3), Item B (refunded: 1, remaining: 2)

#### Full Refund
Refunds all remaining quantities of all items in an order. For example:
- Order has: Item A (qty: 5, refunded: 2), Item B (qty: 3, refunded: 1)
- Full refund: Refunds remaining 3 of Item A and remaining 2 of Item B
- Result: Item A (refunded: 5), Item B (refunded: 3), Order status: FULL

### Refund Flow Architecture

```
Order Service → Payments Service: POST /internal/payments/order/{orderId}/partial-refund
  (with orderItemsToRefund: [{orderItemId, quantity, sellerId, priceCents}])
    ↓
Payments Service:
  - Validates payment state (must be CAPTURED)
  - Creates Stripe refund
  - Stores orderItemsRefunded in refund entity
  - Returns refund (state: REFUNDING)
    ↓
Stripe Webhook → Payments Service: charge.refunded
    ↓
Payments Service:
  - Updates refund state to REFUNDED
  - Publishes RefundCompletedEvent to Kafka
  (includes orderItemsRefunded list)
    ↓
Kafka → Order Service: RefundCompletedEvent
    ↓
Order Service ProcessRefund():
  [BEGIN TRANSACTION]
  1. Check idempotency (skip if already processed)
  2. Validate order exists and matches payment
  3. If orderItemsRefunded is empty → Full refund
     - Get all order items
     - For each item: refund remaining quantity
  4. If orderItemsRefunded has items → Partial refund
     - Get all order items
     - For each item in orderItemsRefunded:
       - Validate item exists and belongs to order
       - Validate seller, price match
       - Validate quantity doesn't exceed available
       - Update refunded_quantity
       - Create audit record
  5. Calculate refund status (NONE/PARTIAL/FULL)
  6. Update order.refund_status
  [COMMIT TRANSACTION]
```

### Refund Processing Details

#### Transaction Safety
All refund operations are wrapped in a database transaction:
- **Atomicity**: Either all updates succeed or all are rolled back
- **Consistency**: Order status always matches item refunded quantities
- **Idempotency**: Processing the same refund event multiple times is safe

#### Tracking Mechanism (Hybrid Approach)

**1. Quick State Queries (`order_items.refunded_quantity`)**
- Direct column for fast queries: "How many items are refunded?"
- Updated incrementally as refunds are processed
- Used for validation and status calculation

**2. Full Audit Trail (`refunded_order_items` table)**
- Records each refund event separately
- Tracks: refund_id, order_item_id, quantity, price, seller, timestamp
- Enables: "When was this item refunded?", "Which refund events affected this item?"
- Supports multiple refunds for the same item

#### Refund Status Calculation

The order's `refund_status` is automatically calculated:
- **NONE**: `totalRefunded == 0`
- **PARTIAL**: `0 < totalRefunded < totalItems`
- **FULL**: `totalRefunded >= totalItems`

Status is updated within the same transaction as item updates, ensuring consistency.

#### Validation

The refund processing includes comprehensive validation:
- **Idempotency**: Checks if refund was already processed (prevents duplicate processing)
- **Order-Payment Match**: Validates order belongs to the payment in the event
- **Item Existence**: Validates order items exist and belong to the order
- **Seller Match**: Validates seller ID matches the original order item
- **Price Match**: Validates price matches the original order item
- **Quantity Validation**: Ensures refund quantity doesn't exceed available quantity
- **Constraint Validation**: Ensures `refunded_quantity <= quantity` before database update

#### Example: Partial Refund Flow

```
Order: Order-123
Items:
  - Item-A: qty=10, refunded=0, price=$5.00
  - Item-B: qty=5, refunded=0, price=$10.00

Refund Request:
  - Item-A: qty=3
  - Item-B: qty=2

Processing:
  1. Validate Item-A exists, belongs to Order-123
  2. Validate seller/price match
  3. Validate: 3 <= (10 - 0) ✅
  4. Update: Item-A.refunded_quantity = 3
  5. Create audit record: refund_id, Item-A, qty=3
  6. Repeat for Item-B
  7. Calculate status: totalRefunded=5, totalItems=15 → PARTIAL
  8. Update: Order-123.refund_status = PARTIAL
  9. Commit transaction

Result:
  - Item-A: qty=10, refunded=3, remaining=7
  - Item-B: qty=5, refunded=2, remaining=3
  - Order-123: refund_status=PARTIAL
```

#### Example: Full Refund Flow

```
Order: Order-123
Items:
  - Item-A: qty=10, refunded=3, price=$5.00
  - Item-B: qty=5, refunded=2, price=$10.00

Refund Request: (empty orderItemsRefunded list)

Processing:
  1. Detect empty list → Full refund
  2. Get all items for Order-123
  3. For Item-A:
     - Calculate remaining: 10 - 3 = 7
     - Update: Item-A.refunded_quantity = 10
     - Create audit record: refund_id, Item-A, qty=7
  4. For Item-B:
     - Calculate remaining: 5 - 2 = 3
     - Update: Item-B.refunded_quantity = 5
     - Create audit record: refund_id, Item-B, qty=3
  5. Calculate status: totalRefunded=15, totalItems=15 → FULL
  6. Update: Order-123.refund_status = FULL
  7. Commit transaction

Result:
  - Item-A: qty=10, refunded=10, remaining=0
  - Item-B: qty=5, refunded=5, remaining=0
  - Order-123: refund_status=FULL
```

### Multiple Refunds

The system supports multiple refunds for the same order item:
- Each refund event is tracked separately in `refunded_order_items`
- `refunded_quantity` is cumulative across all refunds
- Validation ensures total refunded never exceeds original quantity

Example:
```
Item-A: qty=10, refunded=0
Refund 1: qty=3 → refunded=3
Refund 2: qty=2 → refunded=5
Refund 3: qty=4 → refunded=9
Final: qty=10, refunded=9, remaining=1
```

### Error Handling

If any step fails during refund processing:
- Transaction is automatically rolled back
- No partial updates are committed
- Database remains in consistent state
- Event can be safely retried

## Development

See `GO_CRASH_COURSE.md` for a comprehensive guide to Go and the frameworks used in this service.
