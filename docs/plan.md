---

# WEEK 1 — DAY-BY-DAY EXECUTION PLAN

## DAY 1 — Domain modeling + invariants (no production code)

### Deliverables (non-negotiable)

* `docs/invariants.md`
* `docs/architecture.md`
* Payment state machine diagram (ASCII is fine)

---

### Payment State Machine (exact)

```
CREATED
  |
  v
CONFIRMING
  |
  +--> FAILED
  |
  v
AUTHORIZED
  |
  v
CAPTURED
  |
  +--> REFUNDED
  |
  +--> DISPUTED
```

Rules:

* No backward transitions
* No skipping states
* Webhooks may advance state asynchronously

---

### Ledger invariants (write these down verbatim)

* Ledger entries are **append-only**
* Each ledger transaction must balance to zero
* Ledger service rejects unbalanced writes
* Idempotency keys are enforced at the ledger boundary
* Payments service never computes balances

If you can’t enforce an invariant in code, it doesn’t exist.

---

## DAY 2 — Ledger Service (Postgres + correctness)

### Tech

* Spring Boot
* Postgres
* Flyway or Liquibase
* SERIALIZABLE isolation

---

### Ledger Service API (internal)

```http
POST /ledger/transactions
GET  /ledger/transactions/{id}
```

Request:

```json
{
  "idempotencyKey": "uuid",
  "referenceId": "payment_intent_id",
  "entries": [
    { "account": "CUSTOMER", "amount": -100 },
    { "account": "MERCHANT", "amount": 97 },
    { "account": "PLATFORM", "amount": 3 }
  ]
}
```

Ledger service:

* Validates sum == 0
* Writes in a single DB transaction
* Enforces idempotency

---

### Postgres schema (simplified)

```sql
ledger_transactions(
  id,
  reference_id,
  idempotency_key UNIQUE,
  created_at
)

ledger_entries(
  id,
  transaction_id,
  account,
  amount,
  created_at
)
```

No balances table. Ever.

---

## DAY 3 — Payments Service API (no Stripe yet)

### Public API

```http
POST /payment_intents
POST /payment_intents/{id}/confirm
GET  /payments/{id}
```

### PaymentIntent schema

```kotlin
id
amount
currency
status
idempotencyKey
```

### Confirm flow (stubbed)

* Validate state
* Transition to CONFIRMING
* Publish `PaymentConfirmRequested` event

No Stripe calls yet.

---

## DAY 4 — Kafka + orchestration wiring

### Kafka setup

* Strimzi via Helm
* KOWL UI

Topics:

```text
payment.commands
payment.events
payment.dlq
```

### Events

```json
PaymentConfirmRequested
PaymentAuthorized
PaymentCaptured
PaymentFailed
```

### Consumer responsibilities

* Payments service consumes commands
* Emits events
* Retries are bounded
* Failures → DLQ

---

## DAY 5 — Stripe integration + happy path

### Stripe

* Test mode only
* Card payments only

### Implement

* Stripe adapter
* Authorization + capture
* Map Stripe states → internal states

### Happy path

```
CONFIRMING →
AUTHORIZED →
CAPTURED →
Ledger transaction written
```

If Stripe succeeds but ledger fails → **do not retry blindly**. Mark for reconciliation.

---

# WEEK 2 — REALISM & FAILURE

## DAY 6 — Webhooks (critical)

### Stripe webhooks

* Verify signatures
* Store raw events
* Deduplicate by event ID

Webhook may:

* arrive before API response
* arrive twice
* arrive late

Webhook handler must be:

* idempotent
* tolerant to missing state

---

## DAY 7 — Refunds (partial + full)

### Refund API

```http
POST /payments/{id}/refund
```

Ledger:

* New transaction
* Reverse flows
* Fee reversal proportional

Edge cases:

* refund before capture
* duplicate refund
* partial then full

---

## DAY 8 — Failure injection

Simulate:

* Payments crash after Stripe success
* Ledger rejects transaction
* Kafka consumer restarts

Verify:

* No money duplication
* No stuck CONFIRMING forever

---

## DAY 9 — Reconciliation job

Nightly job:

* Fetch Stripe events
* Compare with internal records
* Emit alerts on mismatch

