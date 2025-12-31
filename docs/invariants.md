# System Invariants

This document defines the critical invariants that must be maintained throughout the payments platform. These invariants are non-negotiable and form the foundation of system correctness.

## Core Principle

**If you can't enforce an invariant in code, it doesn't exist.**

All invariants must be enforced programmatically. Documentation alone is insufficient.

---

## Ledger Service Invariants

### 1. Append-Only Ledger

**Invariant:** Ledger entries are immutable and append-only. Once written, entries cannot be modified or deleted.

**Enforcement:**
- Database schema must prevent UPDATE/DELETE operations on ledger entries
- Use database constraints (e.g., `CHECK` constraints, read-only tables, or application-level guards)
- All ledger writes must go through a single, audited code path

**Rationale:** The ledger is the source of truth for all financial transactions. Immutability ensures auditability and prevents tampering.

---

### 2. Balanced Transactions

**Invariant:** Each ledger transaction must balance to zero. The sum of all debits must equal the sum of all credits within a single transaction.

**Enforcement:**
- Ledger service must validate balance before committing any transaction
- Reject any transaction where `SUM(debits) != SUM(credits)` with a clear error
- Use database transactions (SERIALIZABLE isolation) to ensure atomicity
- Consider database-level constraints if possible

**Rationale:** Double-entry bookkeeping requires balanced transactions. Unbalanced transactions would corrupt financial records.

**Example:**
```
✅ Valid:
  DEBIT  account:merchant   $100.00
  CREDIT account:stripe     $100.00
  Total: $0.00

❌ Invalid:
  DEBIT  account:merchant   $100.00
  CREDIT account:stripe     $99.00
  Total: $1.00 (rejected)
```

---

### 3. Idempotency Key Enforcement

**Invariant:** Idempotency keys are enforced at the ledger boundary. Duplicate requests with the same idempotency key must be rejected or return the same result.

**Enforcement:**
- Client may supply idempotency key (optional, but recommended)
- Payments service forwards idempotency key unchanged to ledger service
- Ledger service must maintain a unique constraint on idempotency keys
- All ledger write operations must include an idempotency key
- Duplicate idempotency keys must return the original transaction result (not create a new one)
- Idempotency keys must be scoped appropriately (e.g., per merchant, globally, or per operation type)

**Rationale:** Prevents duplicate processing of financial transactions, which could lead to incorrect balances or double charges.

**Design Decision:** 
- Idempotency keys follow Stripe's model: client supplies, payments service forwards unchanged, ledger enforces.
- Idempotency keys should be enforced at the ledger service boundary, not in the payments service. This ensures that even if the payments service retries, the ledger will not process duplicates.

---

### 4. Payments Service Never Computes Balances

**Invariant:** The payments service must never compute or maintain account balances. All balance queries must go through the ledger service.

**Enforcement:**
- Payments service must not have any balance derivation logic
- Payments service must not cache balances
- All balance queries must be synchronous calls to the ledger service
- Code reviews must verify no balance computation exists in payments service

**Rationale:** The ledger is the system of record for money. Allowing balance computation in the payments service would create multiple sources of truth and potential inconsistencies.

**Design Decision:** This is a strict architectural constraint. If the payments service needs a balance, it must query the ledger service synchronously, even if this impacts latency.

---

### 5. Ledger Writes Only After Money Movement

**Invariant:** The ledger is only written when money movement is guaranteed or has occurred. Payment creation is metadata only and must not trigger ledger writes.

**Enforcement:**
- Ledger writes must only occur after:
  - Authorization (if money movement is guaranteed at authorization)
  - Capture (money has moved)
  - Refund (money has moved)
  - Dispute (money movement has occurred)
- Payment creation must NOT write to ledger (no money has moved yet)
- Code must validate that ledger writes are only triggered by confirmed money movement events
- Webhook handlers must verify money movement before writing to ledger

**Rationale:** The ledger records actual financial transactions. Writing to the ledger before money movement occurs would create incorrect financial records and violate the principle that the ledger is the system of record for money.

**Design Decision:** Payment creation stores metadata in the Payments Service database only. Ledger writes happen asynchronously after Stripe (or other PSP) confirms money movement via webhooks.

---

### 6. Ledger Service Rejects Unbalanced Writes

**Invariant:** The ledger service must reject any write operation that does not balance to zero. This rejection must happen before any database commit.

**Enforcement:**
- Validation must occur in the ledger service application code
- Database transaction must be rolled back if validation fails
- Error must be returned to caller with clear message
- Consider database-level validation as a defense-in-depth measure

**Rationale:** This is the final gatekeeper for financial correctness. Even if upstream services have bugs, the ledger must protect itself.

---

## Payment State Machine Invariants

### 7. No Backward Transitions

**Invariant:** Payment state transitions must only move forward through the state machine. Once a payment reaches a state, it cannot transition to a previous state.

**Enforcement:**
- State machine implementation must validate transitions
- Database constraints or application logic must prevent invalid state changes
- State transition validation must occur before any state update

**State Flow:**
```
CREATED → CONFIRMING → AUTHORIZED → CAPTURED
                                    ↓
                                 REFUNDED
                                    ↓
                                 DISPUTED
```

**Rationale:** Payments represent real-world financial events that are irreversible. Allowing backward transitions would create logical inconsistencies.

---

### 8. No Skipping States

**Invariant:** Payments must transition through states sequentially. States cannot be skipped.

**Enforcement:**
- State machine must validate that transitions are only to the next valid state
- Transition validation must check current state before allowing new state
- Invalid transitions must be rejected with clear error messages

**Valid Transitions:**
- `CREATED` → `CONFIRMING`
- `CONFIRMING` → `AUTHORIZED` or `FAILED`
- `AUTHORIZED` → `CAPTURED`
- `CAPTURED` → `REFUNDED` or `DISPUTED`

**Invalid Transitions:**
- `CREATED` → `AUTHORIZED` (skips CONFIRMING)
- `CONFIRMING` → `CAPTURED` (skips AUTHORIZED)
- `AUTHORIZED` → `REFUNDED` (skips CAPTURED)

**Rationale:** Each state represents a specific stage in the payment lifecycle. Skipping states would indicate a bug or data corruption.

---

### 9. Webhook-Driven Asynchronous State Advancement

**Invariant:** Webhooks from external systems (e.g., Stripe) may advance payment state asynchronously. The system must handle concurrent state updates correctly.

**Enforcement:**
- State updates must be idempotent
- Use optimistic locking or database-level concurrency control
- Webhook handlers must validate state transitions before applying
- Consider using event sourcing or version numbers for state updates

**Rationale:** External systems (PSPs) may send webhooks at any time. The system must handle these correctly even if they arrive out of order or concurrently with other operations.

**Design Decision:** Webhook processing should be idempotent. If a webhook is processed multiple times, it should have the same effect as processing it once.

---

## System Architecture Invariants

### 10. Ledger is the System of Record for Money

**Invariant:** The ledger service and its database are the authoritative system of record for all financial data. No other service may maintain financial state.

**Enforcement:**
- All financial queries must go through the ledger service
- No other service may have financial data in its database
- Payments service may cache non-financial data (e.g., payment metadata) but never balances or amounts

**Rationale:** Having a single system of record prevents inconsistencies and simplifies reasoning about system correctness.

---

### 11. Kafka is Orchestration, Not Money Truth

**Invariant:** Kafka is used for orchestration and event-driven workflows, but it is not the source of truth for financial data. Financial state must be persisted in the ledger database.

**Enforcement:**
- Kafka messages may trigger operations but must not be the only record of financial transactions
- All financial operations must be persisted in the ledger database
- Kafka can be used for retries, but the ledger must be the final authority
- Consider using Kafka for idempotency tracking, but ledger must validate independently

**Rationale:** Kafka is eventually consistent and can have message loss or duplication. Financial data requires strong consistency guarantees.

**Design Decision:** Use Kafka for:
- Orchestrating payment workflows
- Workflow retries
- Event notifications
- Async processing

Do NOT use Kafka for:
- Storing financial balances
- Final financial transaction records
- Balance calculations

---

## Operational Invariants

### 12. Database Isolation Level

**Invariant:** Ledger service database must use SERIALIZABLE isolation level to prevent race conditions and ensure financial correctness.

**Enforcement:**
- Database connection configuration must explicitly set SERIALIZABLE isolation
- Application code must not override this setting
- Monitor for serialization failures and handle retries appropriately

**Rationale:** Financial transactions require the strongest isolation guarantees to prevent anomalies like lost updates, phantom reads, and write skew.

**Design Decision:** Accept that SERIALIZABLE may cause more transaction retries, but this is necessary for correctness. Implement appropriate retry logic with exponential backoff.

---

### 13. Single-Merchant Model

**Invariant:** The system is designed for a single-merchant model. All operations are scoped to a single merchant entity.

**Enforcement:**
- No multi-tenancy concerns in the current design
- All operations implicitly operate on the single merchant
- Future multi-merchant support would require significant architectural changes

**Rationale:** Simplifies the initial implementation and reduces complexity. Multi-merchant support can be added later if needed.

---

## Testing and Validation

### Invariant Verification

All invariants must be verified through:
1. **Unit Tests:** Test invariant enforcement in isolation
2. **Integration Tests:** Test invariant enforcement across service boundaries
3. **Property-Based Tests:** Generate random inputs and verify invariants hold
4. **Database Constraints:** Use database-level enforcement where possible
5. **Code Reviews:** Verify no code violates invariants

### Monitoring

Monitor for invariant violations:
- Track ledger rejection rates (unbalanced transactions, duplicate idempotency keys)
- Alert on invalid state transitions
- Monitor database serialization failures
- Track balance query patterns to ensure payments service isn't computing balances

---

## Summary

These invariants form the foundation of the payments platform. They must be:
- **Enforced in code** - not just documented
- **Tested thoroughly** - unit, integration, and property-based tests
- **Monitored in production** - alerts for violations
- **Reviewed in code reviews** - ensure no violations are introduced

Any violation of these invariants could lead to financial incorrectness, which is unacceptable in a payments system.

