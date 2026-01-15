# Complete System Flow: Product Lifecycle & Buyer Journey

## Part 1: Product Lifecycle (Creation to Sale)

### Phase 1: Seller Onboarding & Product Creation

#### 1. Seller Registration (User Service)
- Seller signs up via Seller Console → User Service
- User Service creates Auth0 user with `account_type: SELLER`
- User Service publishes `UserCreatedEvent` to `user.events`
- Payments Service consumes event → creates ledger account (`SELLER_PAYABLE`) for seller

#### 2. Product Listing (Seller Console → Catalog Service)
**Seller Console → Catalog Service: `POST /api/catalog/products`**

**Flow:**
1. Seller logs into Seller Console (Auth0 JWT with `account_type: SELLER`)
2. Seller fills form: SKU, name, description, category, price, currency, status
3. **Image upload:**
   - Seller uploads file or provides URL
   - Seller Console → Catalog Service: `POST /api/catalog/images/upload` or `/upload-url`
   - Catalog Service uploads to Cloudinary, returns `public_id` and CDN URL
   - Seller Console stores `public_id` in product data
4. **Product creation:**
   - Seller Console → Catalog Service: `POST /api/catalog/products`
   - Catalog Service validates (SELLER/ADMIN only)
   - Extracts `seller_id` from JWT token (`user_id` claim)
   - Stores product in PostgreSQL (`catalog_db`)
   - Publishes `PRODUCT_CREATED` event to `catalog.events`:
     ```json
     {
       "type": "PRODUCT_CREATED",
       "productId": "uuid",
       "sellerId": "seller_123",
       "sku": "SKU-123",
       "name": "Product Name",
       ...
     }
     ```

#### 3. Inventory Initialization (Inventory Service)
**Catalog Service → Kafka → Inventory Service**

**Flow:**
- Inventory Service consumes `PRODUCT_CREATED` from `catalog.events`
- Creates inventory record with `available_quantity = 0`:
  ```sql
  INSERT INTO inventory (product_id, seller_id, sku, available_quantity, total_quantity)
  VALUES (productId, sellerId, sku, 0, 0)
  ```
- Publishes `StockCreatedEvent` to `inventory.events`

#### 4. Stock Management (Seller Console → Inventory Service)
**Seller Console → Inventory Service: `PUT /api/inventory/stock`**

**Flow:**
1. Seller views products in Seller Console
2. Seller Console fetches inventory: `GET /api/inventory/stock/{productId}`
3. Seller updates stock:
   - Seller Console → Inventory Service: `PUT /api/inventory/stock`
   - Inventory Service validates (SELLER/ADMIN only, matches `seller_id`)
   - Updates `total_quantity` and `available_quantity`
   - Uses optimistic locking (version column) with retry logic
   - Uses `SERIALIZABLE` isolation to prevent race conditions
   - Publishes `StockUpdatedEvent` to `inventory.events`

**Edge Cases:**
- Product created but inventory init fails: Inventory Service retries or manual fix
- Concurrent stock updates: Optimistic locking with retry prevents conflicts
- Stock goes negative: Database constraint prevents this

---

## Part 2: Buyer Journey (Happy Path)

### Phase 1: Product Discovery

#### 1. Browse Products (Frontend → Catalog Service)
**Frontend → Catalog Service: `GET /api/catalog/products/browse`**

**Flow:**
- Buyer visits Frontend (Auth0 JWT with `account_type: BUYER`)
- Frontend → Catalog Service: `GET /api/catalog/products/browse`
- Catalog Service returns active products (`status = ACTIVE`)
- Filters by category, price range if provided
- Returns product details + Cloudinary image URLs
- Frontend displays products with images, prices, availability

#### 2. View Product Details
**Frontend → Catalog Service: `GET /api/catalog/products/{id}`**  
**Frontend → Inventory Service: `GET /api/inventory/availability/{productId}`**

**Flow:**
- Buyer clicks product
- Frontend fetches product details from Catalog Service
- Frontend checks availability from Inventory Service
- Frontend shows: name, description, price, images, stock status

### Phase 2: Shopping Cart Operations

#### 1. Add to Cart (Frontend → Cart Service → Inventory Service)
**Frontend → Cart Service: `POST /api/cart/items`**  
**Cart Service → Inventory Service: `POST /api/inventory/reserve` (SOFT)**

**Flow:**
1. Buyer clicks "Add to Cart"
2. Frontend → Cart Service: `POST /api/cart/items` with `{ productId, quantity }`
3. Cart Service validates (BUYER/ADMIN only)
4. Cart Service gets or creates cart in Redis:
   - Key: `cart:{buyerId}`
   - TTL: 15 minutes (auto-extended on updates)
5. Cart Service fetches product details from Catalog Service
6. Cart Service creates soft reservation:
   - Cart Service → Inventory Service: `POST /api/inventory/reserve`
   - Request: `{ inventoryId, cartId, quantity }`
   - Inventory Service:
     - Validates stock availability
     - Creates SOFT reservation (15min TTL)
     - Moves `available_quantity` → `reserved_quantity`
     - Uses optimistic locking with retry
     - Returns `reservationId`
7. Cart Service adds item to Redis cart:
   ```json
   {
     "cartId": "uuid",
     "buyerId": "buyer_123",
     "status": "ACTIVE",
     "items": [{
       "itemId": "uuid",
       "productId": "uuid",
       "sku": "SKU-123",
       "sellerId": "seller_456",
       "quantity": 2,
       "priceCents": 5000,
       "currency": "USD",
       "reservationId": "uuid"
     }],
     "expiresAt": "2024-01-15T10:50:00Z"
   }
   ```
8. Cart Service extends TTL to 15 minutes
9. Cart Service publishes `CartItemAddedEvent` to `cart.events`
10. Cart Service returns updated cart to Frontend

**Edge Cases:**
- Stock unavailable: Inventory Service returns 400, Cart Service returns error to buyer
- Concurrent add-to-cart: Optimistic locking prevents overselling
- Cart expires: Redis TTL expires, cart moved to PostgreSQL (`status = EXPIRED`) by background job

#### 2. Update Cart Item Quantity
**Frontend → Cart Service: `PUT /api/cart/items/{itemId}`**  
**Cart Service → Inventory Service: Update reservation**

**Flow:**
- Buyer changes quantity
- Cart Service updates reservation in Inventory Service
- Cart Service updates Redis cart
- TTL extended

#### 3. Remove from Cart
**Frontend → Cart Service: `DELETE /api/cart/items/{itemId}`**  
**Cart Service → Inventory Service: `POST /api/inventory/release`**

**Flow:**
- Buyer removes item
- Cart Service releases reservation: `POST /api/inventory/release`
- Inventory Service moves `reserved_quantity` → `available_quantity`
- Cart Service removes item from Redis cart

### Phase 3: Checkout & Payment

#### 1. Initiate Checkout (Frontend → Cart Service → Order Service)
**Frontend → Cart Service: `POST /api/cart/checkout`**  
**Cart Service → Order Service: `POST /internal/orders` (create provisional order)**  
**Order Service → Inventory Service: `POST /api/inventory/allocate` (HARD)**  
**Order Service → Payments Service: `POST /api/payments`**

**Flow:**
1. Buyer clicks "Checkout"
2. Frontend → Cart Service: `POST /api/cart/checkout`
3. Cart Service validates cart not empty
4. Cart Service moves cart from Redis to PostgreSQL:
   - Status = `CHECKOUT`
   - Persists cart and cart_items
5. Cart Service calls Order Service (internal API):
   - `POST /internal/orders` with cart data
6. Order Service creates provisional order:
   - Creates order (`status = PENDING`, `provisional = true`)
   - Groups items by seller
   - Creates provisional shipments (one per seller, `provisional = true`)
   - Links order_items to shipments
7. Order Service allocates inventory (HARD lock):
   - For each item: `POST /api/inventory/allocate`
   - Inventory Service:
     - Validates reserved quantity
     - Moves `reserved_quantity` → `allocated_quantity`
     - Creates HARD reservation (30min TTL, references `orderId`)
     - Uses optimistic locking
   - If any allocation fails: Order Service cancels order, returns error
8. Order Service creates payment:
   - Order Service → Payments Service: `POST /api/payments`
   - Request: `{ buyerId, grossAmountCents, currency, orderId }`
   - Payments Service:
     - Creates Stripe PaymentIntent
     - Stores payment (`state = CREATED`, `orderId` linked)
     - Returns `paymentId` and `clientSecret`
9. Order Service returns to Cart Service: `{ orderId, paymentId, clientSecret, checkoutUrl }`
10. Cart Service publishes `CartCheckoutInitiatedEvent` to `cart.events`
11. Cart Service returns checkout URL to Frontend

**Edge Cases:**
- Inventory allocation fails: Order Service cancels provisional order, releases any allocated inventory, returns error
- Payment creation fails: Order Service cancels order, releases inventory
- Concurrent checkout: Optimistic locking prevents double-allocation

#### 2. Payment Processing (Frontend → Stripe → Payments Service)
**Frontend → Stripe: Submit payment with `clientSecret`**  
**Stripe → Payments Service: Webhook (payment captured)**  
**Payments Service → Ledger Service: `POST /ledger/transactions`**  
**Payments Service → Kafka: `PaymentCapturedEvent`**

**Flow:**
1. Buyer completes payment on Stripe checkout page
2. Stripe processes payment
3. Stripe → Payments Service: Webhook (payment captured)
4. Payments Service:
   - Updates payment `state = CAPTURED`
   - Queries Order Service for order items grouped by seller
   - Creates ledger transaction with multiple entries:
     ```json
     {
       "entries": [
         // Buyer payment
         { "accountId": "BUYER_EXTERNAL", "direction": "DEBIT", "amountCents": 10000 },
         { "accountId": "STRIPE_CLEARING", "direction": "CREDIT", "amountCents": 10000 },
         // Seller 1 split (80% of order)
         { "accountId": "STRIPE_CLEARING", "direction": "DEBIT", "amountCents": 8000 },
         { "accountId": "SELLER_PAYABLE_123", "direction": "CREDIT", "amountCents": 7200 },
         { "accountId": "BUYIT_REVENUE", "direction": "CREDIT", "amountCents": 800 },
         // Seller 2 split (20% of order)
         { "accountId": "STRIPE_CLEARING", "direction": "DEBIT", "amountCents": 2000 },
         { "accountId": "SELLER_PAYABLE_456", "direction": "CREDIT", "amountCents": 1800 },
         { "accountId": "BUYIT_REVENUE", "direction": "CREDIT", "amountCents": 200 }
       ]
     }
     ```
   - Publishes `PaymentCapturedEvent` to `payment.events`:
     ```json
     {
       "type": "PAYMENT_CAPTURED",
       "paymentId": "uuid",
       "orderId": "uuid",
       "buyerId": "buyer_123",
       "amountCents": 10000,
       "currency": "USD"
     }
     ```

#### 3. Order Confirmation (Order Service consumes payment event)
**Kafka → Order Service: `PaymentCapturedEvent`**  
**Order Service → Inventory Service: `POST /api/inventory/confirm-sale`**  
**Order Service → Cart Service: Update cart status**  
**Order Service → Kafka: `OrderConfirmedEvent`**

**Flow:**
1. Order Service consumes `PaymentCapturedEvent` from `payment.events`
2. Order Service confirms order:
   - Updates order: `status = CONFIRMED`, `provisional = false`, `confirmed_at = now()`
   - Updates shipments: `provisional = false` (status remains `PENDING`, ready for fulfillment)
3. Order Service confirms inventory sales:
   - For each order item: `POST /api/inventory/confirm-sale`
   - Inventory Service:
     - Moves `allocated_quantity` → sold (deducts from `total_quantity`)
     - Updates reservation `status = SOLD`
     - Publishes `ReservationFulfilledEvent`
4. Order Service updates cart status:
   - Calls Cart Service or publishes event
   - Cart Service updates cart: `status = COMPLETED`
5. Order Service publishes `OrderConfirmedEvent` to `order.events`

**Edge Cases:**
- Payment webhook retry: Payments Service uses idempotency keys
- Order Service down: Event stays in Kafka, processed when service recovers
- Inventory confirmation fails: Order Service retries or manual reconciliation

### Phase 4: Fulfillment & Delivery

#### 1. Seller Processes Order (Seller Console → Fulfillment Service)
**Seller Console → Fulfillment Service: `GET /api/shipments`**  
**Seller Console → Fulfillment Service: `PUT /api/shipments/{id}/process`**

**Flow:**
1. Seller logs into Seller Console
2. Seller Console → Fulfillment Service: `GET /api/shipments` (filters by `sellerId`)
3. Fulfillment Service returns shipments for seller (`status = PENDING`, `provisional = false`)
4. Seller clicks "Process Order"
5. Seller Console → Fulfillment Service: `PUT /api/shipments/{id}/process`
6. Fulfillment Service updates shipment: `status = PROCESSING`
7. Fulfillment Service publishes `ShipmentStatusUpdatedEvent` to `order.events`
8. Order Service consumes event, aggregates shipment statuses:
   - If any shipment = `PROCESSING` → order status = `PROCESSING`

#### 2. Seller Ships Order
**Seller Console → Fulfillment Service: `PUT /api/shipments/{id}/ship`**

**Flow:**
1. Seller adds tracking number and carrier
2. Seller Console → Fulfillment Service: `PUT /api/shipments/{id}/ship`
3. Request: `{ trackingNumber: "1Z999AA10123456784", carrier: "UPS" }`
4. Fulfillment Service updates shipment:
   - `status = SHIPPED`
   - `tracking_number = "1Z999AA10123456784"`
   - `carrier = "UPS"`
   - `shipped_at = now()`
5. Fulfillment Service publishes `ShipmentStatusUpdatedEvent`
6. Order Service aggregates:
   - If all shipments = `SHIPPED` → order status = `SHIPPED`

#### 3. Delivery Confirmation
**Carrier → Fulfillment Service: Webhook (delivery confirmed)**  
**Fulfillment Service → Order Service: `ShipmentStatusUpdatedEvent`**

**Flow:**
1. Carrier delivers package
2. Carrier → Fulfillment Service: Webhook `POST /api/shipments/{id}/delivered`
3. Fulfillment Service updates shipment:
   - `status = DELIVERED`
   - `delivered_at = now()`
4. Fulfillment Service publishes `ShipmentStatusUpdatedEvent`
5. Order Service aggregates:
   - If all shipments = `DELIVERED` → order status = `DELIVERED`
6. Order complete

---

## Part 3: Edge Cases & Error Handling

### Payment Failure Flow

#### 1. Payment Declined/Failed
**Stripe → Payments Service: Webhook (payment failed)**  
**Payments Service → Kafka: `PaymentFailedEvent`**  
**Order Service → Inventory Service: Release inventory**

**Flow:**
1. Stripe → Payments Service: Payment failed webhook
2. Payments Service publishes `PaymentFailedEvent` to `payment.events`
3. Order Service consumes event:
   - Updates order: `status = CANCELLED`, `cancelled_at = now()`
   - Updates shipments: `status = CANCELLED`
   - Releases inventory: `POST /api/inventory/release` for each item
   - Inventory Service moves `allocated_quantity` → `available_quantity`
4. Cart Service updates cart: `status = ABANDONED`
5. Background job cleans up provisional orders older than threshold

### Cart Expiration

**Flow:**
1. Buyer adds items to cart
2. Buyer abandons cart (no checkout within 15 minutes)
3. Redis TTL expires
4. Background job (Cart Service):
   - Detects expired carts in Redis
   - Moves to PostgreSQL: `status = EXPIRED`
   - Releases soft reservations in Inventory Service
   - Publishes `CartAbandonedEvent` to `cart.events`

### Inventory Overselling Prevention

**Mechanisms:**
- Database constraints: `CHECK (available_quantity + reserved_quantity + allocated_quantity <= total_quantity)`
- Optimistic locking: Version column with retry logic
- `SERIALIZABLE` isolation: Prevents concurrent race conditions
- Reservation TTL: Soft reservations expire after 15min, hard after 30min

**Example:**
- Product has 5 units available
- Buyer A adds 3 to cart (soft reserve: 3 reserved, 2 available)
- Buyer B adds 3 to cart (fails: only 2 available)
- Buyer A checks out (hard allocate: 3 allocated, 2 available)
- Buyer C adds 2 to cart (succeeds: 2 reserved, 0 available)

### Multi-Seller Order Handling

**Scenario:** Buyer adds items from Seller A and Seller B to cart

**Flow:**
1. Cart contains items from both sellers
2. Checkout creates:
   - One order with all items
   - One payment for total amount
   - Two shipments (one per seller)
3. Payment success:
   - Ledger splits payment:
     - Seller A receives their portion (90% after 10% platform fee)
     - Seller B receives their portion (90% after 10% platform fee)
     - Platform receives fees from both
4. Each seller fulfills their shipment independently
5. Order completes when both shipments are delivered

---

## Part 4: Service Interaction Summary

### Direct HTTP Calls

- **Frontend ↔ Catalog Service:** Product browsing, details
- **Frontend ↔ Cart Service:** Cart operations
- **Frontend ↔ Payments Service:** Payment processing
- **Seller Console ↔ Catalog Service:** Product CRUD
- **Seller Console ↔ Inventory Service:** Stock management
- **Seller Console ↔ Fulfillment Service:** Shipment management
- **Cart Service ↔ Inventory Service:** Soft/hard reservations
- **Cart Service ↔ Order Service:** Checkout orchestration
- **Order Service ↔ Inventory Service:** Allocation, confirmation
- **Order Service ↔ Payments Service:** Payment creation
- **Payments Service ↔ Ledger Service:** Financial transactions
- **Payments Service ↔ Order Service:** Query order items for splitting

### Event-Driven (Kafka)

- **Catalog Service → Inventory Service:** `PRODUCT_CREATED` (inventory init)
- **Inventory Service → Cart Service:** `StockUpdatedEvent`, `LowStockAlertEvent`
- **Cart Service → Analytics:** `CartCreatedEvent`, `CartItemAddedEvent`, `CartAbandonedEvent`
- **Payments Service → Order Service:** `PaymentCapturedEvent`, `PaymentFailedEvent`
- **Order Service → Analytics:** `OrderConfirmedEvent`, `OrderCancelledEvent`
- **Fulfillment Service → Order Service:** `ShipmentStatusUpdatedEvent`

### Data Flow Patterns

- **Synchronous:** Critical operations (payment creation, inventory allocation)
- **Asynchronous:** Non-critical operations (events, analytics)
- **Hybrid:** Checkout (sync for allocation, async for confirmation)

---

## Part 5: Admin Console Operations

Admin can:
- View all products: `GET /api/catalog/products/by-seller/{sellerId}`
- View all orders: `GET /api/orders` (all buyers)
- View all shipments: `GET /api/shipments` (all sellers)
- Manage inventory: Same as sellers
- View analytics: Consume events from Kafka topics

---

## Architecture Benefits

This architecture supports:
- **Scalability:** Services scale independently
- **Reliability:** Event-driven with retries and idempotency
- **Consistency:** Database constraints and optimistic locking prevent data corruption
- **Flexibility:** Easy to add new features (search, recommendations) via events

The hybrid order approach balances inventory protection with a smooth buyer experience, following common marketplace patterns.
