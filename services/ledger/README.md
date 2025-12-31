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

## Testing

### Run Tests

```bash
./gradlew test
```

### Concurrency Tests

The concurrency tests verify:
- Balance correctness under concurrent writes
- Idempotency enforcement
- Overdraft prevention
- Balance query consistency

```bash
./gradlew test --tests "*ConcurrencyTest"
```

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

