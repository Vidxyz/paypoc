# Ledger Service

The Ledger Service is the system of record for all financial transactions in the payments platform. It enforces financial correctness through strict invariants and SERIALIZABLE database isolation.

## Features

- **Append-only ledger**: Immutable transaction records
- **Double-entry bookkeeping**: All transactions must balance
- **No overdrafts**: Balance checks prevent negative balances
- **Idempotency**: Unique idempotency keys prevent duplicate processing
- **Concurrency safety**: SERIALIZABLE isolation ensures correctness under concurrent access

## API Endpoints

### Create Transaction

```http
POST /ledger/transactions
Content-Type: application/json

{
  "accountId": "550e8400-e29b-41d4-a716-446655440000",
  "amountCents": -2500,
  "currency": "USD",
  "idempotencyKey": "refund_abc_123",
  "description": "Refund for order #123"
}
```

**Response:**
```json
{
  "transactionId": "660e8400-e29b-41d4-a716-446655440000",
  "accountId": "550e8400-e29b-41d4-a716-446655440000",
  "amountCents": -2500,
  "currency": "USD",
  "idempotencyKey": "refund_abc_123",
  "description": "Refund for order #123",
  "createdAt": "2024-01-15T10:30:00Z"
}
```

### Get Balance

```http
GET /ledger/accounts/{accountId}/balance
```

**Response:**
```json
{
  "accountId": "550e8400-e29b-41d4-a716-446655440000",
  "currency": "USD",
  "balanceCents": 125000
}
```

## Database Schema

### ledger_accounts
- `account_id` (UUID, PRIMARY KEY)
- `currency` (CHAR(3), NOT NULL)
- `created_at` (TIMESTAMP)

### ledger_account_metadata
- `account_id` (UUID, PRIMARY KEY, FK to ledger_accounts)
- `user_id` (TEXT)
- `user_email` (TEXT)
- `display_name` (TEXT)
- `metadata` (JSONB)
- `created_at` (TIMESTAMP)
- `updated_at` (TIMESTAMP)

### ledger_transactions
- `transaction_id` (UUID, PRIMARY KEY)
- `account_id` (UUID, NOT NULL, FK to ledger_accounts)
- `amount_cents` (BIGINT, NOT NULL, CHECK: <> 0)
- `currency` (CHAR(3), NOT NULL)
- `idempotency_key` (TEXT, NOT NULL, UNIQUE)
- `description` (TEXT)
- `created_at` (TIMESTAMP)

## Running the Service

### Prerequisites
- Java 17+
- PostgreSQL 15+
- Gradle 7+

### Setup Database

```bash
# Create database
createdb ledger_db

# Or using Docker
docker run -d \
  --name ledger-postgres \
  -e POSTGRES_DB=ledger_db \
  -e POSTGRES_USER=ledger_user \
  -e POSTGRES_PASSWORD=ledger_password \
  -p 5432:5432 \
  postgres:15-alpine
```

### Run Application

```bash
cd services/ledger
./gradlew bootRun
```

The service will start on port 8081 and automatically run Flyway migrations.

## API Documentation

Once the service is running, you can access the interactive OpenAPI/Swagger documentation:

- **Swagger UI**: http://localhost:8081/swagger-ui.html
- **OpenAPI JSON**: http://localhost:8081/v3/api-docs
- **OpenAPI YAML**: http://localhost:8081/v3/api-docs.yaml

The Swagger UI provides:
- Interactive API testing interface
- Complete request/response schemas
- Example values for all fields
- Try-it-out functionality to test endpoints directly from the browser

## Testing

### Prerequisites

Tests require a local PostgreSQL instance running. The tests will automatically create and destroy the test database.

**Option 1: Use Docker (Recommended for Development)**
```bash
docker run -d --name ledger-postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:15-alpine
```

**Option 2: Use Local PostgreSQL Installation**
- Ensure PostgreSQL 15+ is installed and running
- Default connection: `localhost:5432` with user `postgres` / password `postgres`
- You can override with environment variables: `DB_USERNAME` and `DB_PASSWORD`

### Run Tests

```bash
./gradlew test
```

The tests will:
1. **Before all tests**: Create a fresh `ledger_test` database
2. **Run all tests**: Execute concurrency and correctness tests
3. **After all tests**: Drop the `ledger_test` database

### Concurrency Tests

The concurrency tests verify:
- Balance correctness under concurrent writes
- Idempotency enforcement
- Overdraft prevention
- Balance query consistency

```bash
./gradlew test --tests "*ConcurrencyTest"
```

### Test Configuration

Test configuration is in `src/test/resources/application-test.yml`:
- Database: `ledger_test` (created/dropped automatically)
- Connection: `localhost:5432`
- Credentials: Can be overridden with `DB_USERNAME` and `DB_PASSWORD` environment variables

## Invariants Enforced

1. **Atomicity**: All transaction operations are atomic via database transactions
2. **No Overdrafts**: Balance checks prevent negative balances
3. **Idempotency**: Unique constraint on `idempotency_key` prevents duplicates
4. **Concurrency Safety**: SERIALIZABLE isolation ensures correctness under concurrent access
5. **Append-Only**: Transactions are immutable once written
6. **Balanced Transactions**: Each transaction must balance (enforced by application logic)

## Configuration

See `src/main/resources/application.yml` for configuration options:

- Database connection settings
- SERIALIZABLE isolation level
- Flyway migration settings
- Connection pool configuration

