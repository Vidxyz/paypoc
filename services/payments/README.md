# Payments Service

The Payments Service is an **orchestration layer** that coordinates payment workflows. It is **NOT** a source of financial truth - the Ledger Service is.

## Key Principles

1. **Seller Stripe Account Required**: Seller Stripe accounts must be registered via internal API before processing payments
2. **Ledger Writes After Capture**: Ledger writes only occur after Stripe webhook confirms payment capture (not at creation)
3. **Workflow State Only**: Payments database stores orchestration state, not financial truth
4. **No Balance Computation**: Payments never computes balances - it always delegates to Ledger
5. **State Machine Enforcement**: Payment state transitions are explicitly defined and enforced

## Architecture

```
Client Request
    ↓
Payments Service (Orchestration)
    ↓
Stripe PaymentIntent Created
    ↓
Payment Record Created (state = CREATED, ledger_transaction_id = NULL)
    ↓
Stripe Webhook Confirms Capture
    ↓
Ledger Service (Source of Truth) ← Called AFTER money movement confirmed
    ↓
Payment Updated (ledger_transaction_id set)
```

### Critical Invariant

> **If payment exists with ledger_transaction_id, money exists in ledger.**

The ledger write happens **after** Stripe confirms money movement via webhook:
- Payment creation: NO ledger write (no money has moved yet)
- Stripe webhook confirms capture: THEN write to ledger
- This ensures: "Ledger only records actual money movement"

## Database Schema

The `payments` table stores **workflow state only**:

- `amount_cents`: Metadata, not balance
- `ledger_transaction_id`: Reference to ledger, not truth
- `state`: Workflow state (CREATED, CONFIRMING, AUTHORIZED, CAPTURED, FAILED)
- `idempotency_key`: For idempotent operations

**If this database is deleted, money is still correct in the ledger.**

## Payment State Machine

Explicit state transitions enforced by `PaymentStateMachine`:

```
CREATED → CONFIRMING → AUTHORIZED → CAPTURED
   ↓           ↓            ↓
 FAILED     FAILED      FAILED
```

Invalid transitions throw `IllegalArgumentException`.

## API Endpoints

### POST /payments

Creates a new payment and Stripe PaymentIntent. The seller's Stripe account is automatically looked up from the database based on `sellerId` and `currency`.

**Important**: The seller's Stripe account must be registered via the internal API before payments can be processed.

**Request:**
```json
{
  "buyerId": "buyer_123",
  "sellerId": "seller_456",
  "grossAmountCents": 10000,
  "currency": "USD",
  "description": "Payment for order #123"
}
```

**Response (201 Created):**
```json
{
  "id": "660e8400-e29b-41d4-a716-446655440000",
  "buyerId": "buyer_123",
  "sellerId": "seller_456",
  "grossAmountCents": 10000,
  "platformFeeCents": 1000,
  "netSellerAmountCents": 9000,
  "currency": "USD",
  "state": "CREATED",
  "stripePaymentIntentId": "pi_1234567890",
  "clientSecret": "pi_1234567890_secret_abc123",
  "ledgerTransactionId": null,
  "idempotencyKey": "payment_...",
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2024-01-15T10:30:00Z"
}
```

**Error (400 Bad Request):**
```json
{
  "error": "Payment creation failed: Seller seller_456 does not have a Stripe account configured for currency USD. Please configure the seller's Stripe account before processing payments."
}
```

**Note**: `ledgerTransactionId` is `null` at creation time. The ledger write happens **after** Stripe webhook confirms payment capture.

### GET /payments/{paymentId}

Gets a payment by ID.

### GET /balance?accountId={accountId}

Gets balance for an account. **Delegates to Ledger Service** - Payments never computes balances.

## Internal API Endpoints

### Seller Stripe Account Management

The Payments Service provides internal API endpoints for managing seller Stripe account mappings. These endpoints require authentication via an opaque token.

**Authentication**: All internal API requests must include:
```
Authorization: Bearer {token}
```

The token is configured via `payments.internal.api.token` in `application.yml`.

#### POST /internal/sellers/{sellerId}/stripe-accounts

Registers a seller's Stripe account for a specific currency.

**Request:**
```json
{
  "stripeAccountId": "acct_1234567890",
  "currency": "USD"
}
```

**Response (201 Created):**
```json
{
  "sellerId": "seller_456",
  "stripeAccountId": "acct_1234567890",
  "currency": "USD",
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2024-01-15T10:30:00Z"
}
```

**Note**: If an account already exists for the seller and currency, it will be updated (returns 200 OK).

#### GET /internal/sellers/{sellerId}/stripe-accounts

Gets all Stripe accounts for a seller (across all currencies).

**Response (200 OK):**
```json
[
  {
    "sellerId": "seller_456",
    "stripeAccountId": "acct_1234567890",
    "currency": "USD",
    "createdAt": "2024-01-15T10:30:00Z",
    "updatedAt": "2024-01-15T10:30:00Z"
  },
  {
    "sellerId": "seller_456",
    "stripeAccountId": "acct_0987654321",
    "currency": "EUR",
    "createdAt": "2024-01-15T10:30:00Z",
    "updatedAt": "2024-01-15T10:30:00Z"
  }
]
```

#### GET /internal/sellers/{sellerId}/stripe-accounts/{currency}

Gets a seller's Stripe account for a specific currency.

**Response (200 OK):**
```json
{
  "sellerId": "seller_456",
  "stripeAccountId": "acct_1234567890",
  "currency": "USD",
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2024-01-15T10:30:00Z"
}
```

**Response (404 Not Found):**
```json
{
  "error": "Seller Stripe account not found for seller seller_456 and currency USD"
}
```

#### DELETE /internal/sellers/{sellerId}/stripe-accounts/{currency}

Removes a seller's Stripe account for a specific currency.

**Response (200 OK):**
```json
{
  "sellerId": "seller_456",
  "stripeAccountId": "acct_1234567890",
  "currency": "USD",
  "message": "Seller Stripe account deleted successfully"
}
```

### Example: Registering a Seller Stripe Account

Before processing payments for a seller, you must register their Stripe account:

**Using curl:**
```bash
curl -X POST http://localhost:8080/internal/sellers/seller_456/stripe-accounts \
  -H "Authorization: Bearer internal-test-token-change-in-production" \
  -H "Content-Type: application/json" \
  -d '{
    "stripeAccountId": "acct_1234567890",
    "currency": "USD"
  }'
```

**Using the provided script:**
```bash
cd services/payments/scripts
./register-seller-account.sh seller_456 acct_1234567890 USD
```

**Environment variables:**
```bash
export PAYMENTS_SERVICE_URL=http://localhost:8080
export INTERNAL_API_TOKEN=internal-test-token-change-in-production
./register-seller-account.sh seller_456 acct_1234567890 USD
```

### Example: Complete Payment Flow

**Step 1: Register seller Stripe account** (if not already registered)

```bash
./scripts/register-seller-account.sh seller_456 acct_1234567890 USD
```

**Step 2: Create payment**

```bash
curl -X POST http://localhost:8080/payments \
  -H "Content-Type: application/json" \
  -d '{
    "buyerId": "buyer_123",
    "sellerId": "seller_456",
    "grossAmountCents": 10000,
    "currency": "USD",
    "description": "Payment for order #123"
  }'
```

**Or use the complete example script:**
```bash
cd services/payments/scripts
./create-payment-example.sh buyer_123 seller_456 acct_1234567890 10000 USD
```

This script will:
1. Register the seller Stripe account (if needed)
2. Create the payment
3. Display the payment details including `clientSecret` for frontend use

## Running the Service

### Prerequisites

- Java 17+
- PostgreSQL 15+
- Ledger Service running on port 8081

### Setup Database

```bash
# Create database
createdb payments_db

# Or using Docker
docker run -d \
  --name payments-postgres \
  -e POSTGRES_DB=payments_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5433:5432 \
  postgres:15-alpine
```

### Run Application

```bash
cd services/payments
export DB_USERNAME=postgres  # Optional
export DB_PASSWORD=postgres  # Optional
./gradlew bootRun
```

The service will start on port 8080 and automatically run Flyway migrations.

**Important**: Ensure the Ledger Service is running on port 8081 before starting Payments Service.

## API Documentation

Once the service is running, you can access the interactive OpenAPI/Swagger documentation:

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI JSON**: http://localhost:8080/v3/api-docs
- **OpenAPI YAML**: http://localhost:8080/v3/api-docs.yaml

The Swagger UI provides:
- Interactive API testing interface
- Complete request/response schemas
- Example values for all fields
- Try-it-out functionality to test endpoints directly from the browser

## Testing

### Prerequisites

Tests require:
- Local PostgreSQL instance running
- Ledger Service running on port 8081 (for integration tests)
- Java 17+

### Run All Tests

```bash
./gradlew test
```

The tests will:
1. **Before all tests**: Create a fresh `payments_test` database
2. **Run all tests**: Execute all unit and integration tests
3. **After all tests**: Drop the `payments_test` database

### Integration Tests

#### Payment-Ledger Integration Test

The critical integration test `PaymentLedgerIntegrationTest` proves:

- Payments cannot override ledger correctness
- Ledger failure propagates up
- No payment record exists if ledger rejects
- Ledger balance is unchanged if payment creation fails

This is a **design proof**, not just a unit test.

**Run this test:**
```bash
./gradlew test --tests PaymentLedgerIntegrationTest
```

**Prerequisites:**
- Ledger Service must be running on port 8081
- PostgreSQL must be running on localhost:5432

#### Kafka Resilience Tests

The `KafkaPaymentResilienceTest` suite verifies Kafka-based payment processing resilience:

- **Idempotency**: Duplicate commands don't cause duplicate side effects
- **Resilience**: Consumer failures don't corrupt state
- **Correctness**: Ledger state remains correct under all scenarios
- **State machine**: Payment state transitions are correct

**Test scenarios:**
1. Consumer killed mid-processing (message replay)
2. Duplicate commands (idempotency verification)
3. Handler exceptions (error handling)
4. Message replay after partial processing
5. Ledger state unchanged during Kafka processing

**Run this test:**
```bash
./gradlew test --tests KafkaPaymentResilienceTest
```

**Prerequisites:**
- PostgreSQL must be running on localhost:5432
- **No Ledger Service required** (uses mocked `LedgerClient`)
- **EmbeddedKafka** is automatically started by Spring Test (no external Kafka needed)

**Test behavior:**
- Uses EmbeddedKafka for in-memory Kafka testing
- Creates/tears down `payments_test` database automatically
- Mocks `LedgerClient` to isolate Kafka processing logic
- Verifies idempotency, state correctness, and error handling

**Running individual test methods:**
```bash
# Test duplicate commands
./gradlew test --tests KafkaPaymentResilienceTest.duplicate\ commands\ should\ be\ handled\ idempotently

# Test message replay
./gradlew test --tests KafkaPaymentResilienceTest.message\ replay\ after\ partial\ processing\ should\ be\ idempotent

# Test exception handling
./gradlew test --tests KafkaPaymentResilienceTest.handler\ exception\ should\ not\ corrupt\ state
```

## Configuration

See `src/main/resources/application.yml`:

- Database connection settings
- Ledger Service URL (`ledger.service.url`)
- Server port (8080)

## Seller Stripe Account Management

### Overview

The Payments Service requires seller Stripe accounts to be registered before processing payments. This ensures:

- **Security**: Clients cannot specify arbitrary Stripe accounts
- **Single Source of Truth**: Seller → Stripe account mapping stored in backend
- **Multi-Currency Support**: Different Stripe accounts per currency per seller
- **Clean API**: Clients only need to provide `sellerId`, not Stripe account details

### Registration Flow

1. **Seller Onboarding**: When a seller signs up, register their Stripe connected account via internal API
2. **Payment Processing**: When creating a payment, the service automatically looks up the seller's Stripe account
3. **Error Handling**: If seller account is not configured, payment creation fails with clear error message

### Helper Scripts

The `scripts/` directory contains helper scripts for common operations:

**Register Seller Stripe Account:**
```bash
cd services/payments/scripts
./register-seller-account.sh seller_456 acct_1234567890 USD
```

**Complete Payment Flow Example:**
```bash
cd services/payments/scripts
./create-payment-example.sh buyer_123 seller_456 acct_1234567890 10000 USD
```

These scripts handle:
- Input validation
- Error handling
- Pretty-printed JSON responses
- Environment variable configuration

### Database Schema

The `seller_stripe_accounts` table stores the mapping:

- `seller_id` (TEXT, PRIMARY KEY)
- `currency` (TEXT, PRIMARY KEY)
- `stripe_account_id` (TEXT, NOT NULL)
- `created_at` (TIMESTAMPTZ)
- `updated_at` (TIMESTAMPTZ)

Composite primary key: `(seller_id, currency)` - supports multiple currencies per seller.

## Invariants Enforced

1. **Ledger Writes After Money Movement**: Ledger writes only occur after Stripe webhook confirms payment capture
2. **No Balance Computation**: Payments never computes balances
3. **State Machine**: Payment state transitions are explicit and enforced
4. **Workflow State Only**: Payments database stores orchestration state, not financial truth

## Dependencies

- **Ledger Service**: Hard dependency (synchronous HTTP calls)
- **PostgreSQL**: For workflow state storage
- **Spring Boot**: Framework
- **Flyway**: Database migrations
