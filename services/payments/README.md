# Payments Service

The Payments Service is an **orchestration layer** that coordinates payment workflows. It is **NOT** a source of financial truth - the Ledger Service is.

## Key Principles

1. **Ledger-First Approach**: Payments always calls the Ledger Service BEFORE creating a payment record
2. **Workflow State Only**: Payments database stores orchestration state, not financial truth
3. **No Balance Computation**: Payments never computes balances - it always delegates to Ledger
4. **State Machine Enforcement**: Payment state transitions are explicitly defined and enforced

## Architecture

```
Client Request
    ↓
Payments Service (Orchestration)
    ↓
Ledger Service (Source of Truth) ← Called FIRST
    ↓
Payment Record Created (if ledger succeeds)
```

### Critical Invariant

> **If payment exists, money exists.**

This is enforced by the ledger-first approach:
- If ledger rejects → payment is NOT created
- If ledger accepts → payment is created with `ledger_transaction_id`

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

Creates a new payment using ledger-first approach.

**Request:**
```json
{
  "accountId": "550e8400-e29b-41d4-a716-446655440000",
  "amountCents": 1000,
  "currency": "USD",
  "description": "Payment for order #123"
}
```

**Response (201 Created):**
```json
{
  "id": "660e8400-e29b-41d4-a716-446655440000",
  "amountCents": 1000,
  "currency": "USD",
  "state": "CREATED",
  "ledgerTransactionId": "770e8400-e29b-41d4-a716-446655440000",
  "idempotencyKey": "payment_...",
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2024-01-15T10:30:00Z"
}
```

**Error (400 Bad Request):**
```json
{
  "error": "Payment creation failed: Ledger rejected transaction. Insufficient funds..."
}
```

### GET /payments/{paymentId}

Gets a payment by ID.

### GET /balance?accountId={accountId}

Gets balance for an account. **Delegates to Ledger Service** - Payments never computes balances.

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
- Ledger Service running on port 8081

### Run Tests

```bash
./gradlew test
```

The tests will:
1. **Before all tests**: Create a fresh `payments_test` database
2. **Run all tests**: Execute integration tests
3. **After all tests**: Drop the `payments_test` database

### Integration Test

The critical integration test `PaymentLedgerIntegrationTest` proves:

- Payments cannot override ledger correctness
- Ledger failure propagates up
- No payment record exists if ledger rejects
- Ledger balance is unchanged if payment creation fails

This is a **design proof**, not just a unit test.

## Configuration

See `src/main/resources/application.yml`:

- Database connection settings
- Ledger Service URL (`ledger.service.url`)
- Server port (8080)

## What We Deliberately Do NOT Build Yet

These are **not** implemented yet (by design):

- ❌ Stripe integration
- ❌ Kafka/async processing
- ❌ Webhooks
- ❌ Retries
- ❌ Refunds
- ❌ Captures

Why? Because once async enters, reasoning becomes harder. We lock correctness **first**.

## Invariants Enforced

1. **Ledger-First**: Payment creation always calls ledger first
2. **No Balance Computation**: Payments never computes balances
3. **State Machine**: Payment state transitions are explicit and enforced
4. **Workflow State Only**: Payments database stores orchestration state, not financial truth

## Dependencies

- **Ledger Service**: Hard dependency (synchronous HTTP calls)
- **PostgreSQL**: For workflow state storage
- **Spring Boot**: Framework
- **Flyway**: Database migrations

