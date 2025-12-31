# System Architecture

This document describes the overall architecture of the payments platform, including service design, data flow, technology choices, and deployment strategy.

## Overview

The payments platform is a monorepo-based system designed to handle payment processing with strict financial correctness guarantees. The system follows a two-service architecture where the Ledger Service maintains financial truth and the Payments Service orchestrates payment workflows.

## High-Level Architecture

```
┌─────────────┐
│   Client    │
│  (External) │
└──────┬──────┘
       │
       │ HTTP/REST
       │
┌──────▼─────────────────────────────────────┐
│         Payments Service                    │
│  - Payment orchestration                    │
│  - PSP integration (Stripe)                 │
│  - Public APIs                              │
│  - Webhook handling                         │
└──────┬──────────────────┬──────────────────┘
       │                  │
       │ Sync HTTP        │ Async Kafka
       │                  │
       ▼                  ▼
┌─────────────────┐  ┌──────────────┐
│ Ledger Service  │  │    Kafka     │
│ - Money truth   │  │ (Strimzi)    │
│ - Balance calc  │  │ - Events     │
│ - Transactions  │  │ - Retries    │
└────────┬────────┘  └──────────────┘
         │
         │
         ▼
┌─────────────────┐
│    Postgres     │
│  (SERIALIZABLE) │
│  - Ledger DB    │
└─────────────────┘
```

## Core Principles

1. **Ledger is the Source of Truth**: All financial data resides in the Ledger Service database
2. **Payments Service Never Writes Balances**: Payments service orchestrates but never computes or stores balances
3. **Kafka is Orchestration, Not Money Truth**: Kafka handles workflow orchestration but financial state is in the ledger
4. **Single-Merchant Model**: System designed for a single merchant entity

## Services

### Payments Service

**Responsibilities:**
- Payment orchestration and workflow management
- Integration with Payment Service Providers (PSPs) - primarily Stripe
- Public-facing REST APIs for payment operations
- Webhook handling from external systems
- Payment state machine management
- Idempotency key generation and forwarding

**What it does NOT do:**
- Compute or store account balances
- Maintain financial transaction records (beyond payment metadata)
- Act as source of truth for financial data

**Key Operations:**
- Create payment
- Confirm payment
- Handle PSP webhooks
- Query payment status
- Initiate refunds
- Handle disputes

**Technology:**
- Kotlin
- Spring Boot
- REST APIs
- Stripe SDK

### Ledger Service

**Responsibilities:**
- Maintain financial transaction records (append-only ledger)
- Compute and serve account balances
- Enforce double-entry bookkeeping (balanced transactions)
- Enforce idempotency at the ledger boundary
- Validate all financial operations

**Key Operations:**
- Create ledger entries (with balance validation)
- Query account balances
- Validate transaction balance (debits = credits)
- Enforce idempotency keys
- Provide financial audit trail

**Technology:**
- Kotlin
- Spring Boot
- PostgreSQL with SERIALIZABLE isolation
- REST APIs (internal)

**Database:**
- PostgreSQL
- SERIALIZABLE isolation level
- Append-only ledger table structure
- Unique constraints on idempotency keys

## Data Flow

### Payment Creation Flow

```
1. Client → Payments Service: POST /payments
   - Payments Service generates idempotency key
   - Creates payment record in CREATED state

2. Payments Service → Ledger Service: POST /ledger/transactions
   - Synchronous call
   - Includes idempotency key
   - Ledger validates and records transaction
   - Returns success/failure

3. Payments Service → Stripe: Create payment intent
   - Async operation
   - Payment state → CONFIRMING

4. Stripe → Payments Service: Webhook (async)
   - Payment state → AUTHORIZED or FAILED

5. Payments Service → Ledger Service: Update transaction
   - If authorized, record capture
   - If failed, record reversal
```

### Payment Capture Flow

```
1. Client → Payments Service: POST /payments/{id}/capture
   - Payments Service validates state (must be AUTHORIZED)

2. Payments Service → Stripe: Capture payment
   - Async operation

3. Stripe → Payments Service: Webhook (async)
   - Payment state → CAPTURED

4. Payments Service → Ledger Service: Record capture
   - Synchronous call
   - Ledger records capture transaction
```

### Balance Query Flow

```
1. Client → Payments Service: GET /balance
   - Payments Service does NOT compute balance

2. Payments Service → Ledger Service: GET /ledger/balance
   - Synchronous call
   - Ledger queries database and returns balance

3. Payments Service → Client: Returns balance
```

## Payment State Machine

```
CREATED
  │
  ▼
CONFIRMING
  │
  ├───► FAILED (terminal)
  │
  ▼
AUTHORIZED
  │
  ▼
CAPTURED
  │
  ├───► REFUNDED (terminal)
  │
  └───► DISPUTED (terminal)
```

**Rules:**
- No backward transitions
- No skipping states
- Webhooks may advance state asynchronously
- State transitions are validated before updates

See `invariants.md` for detailed state machine rules.

## Technology Stack

### Language & Framework
- **Kotlin**: Primary language for all services
- **Spring Boot**: Framework for both services
  - Spring Web (REST APIs)
  - Spring Data JPA (database access)
  - Spring Kafka (Kafka integration)

### Messaging
- **Kafka (Strimzi)**: Event-driven orchestration
  - Topic-based messaging
  - Used for retries, async workflows, event notifications
  - **Not used for financial state storage**

### Database
- **PostgreSQL**: Ledger Service database
  - SERIALIZABLE isolation level
  - Append-only ledger structure
  - Strong consistency guarantees

### Infrastructure
- **Minikube**: Local development environment
- **GKE (Google Kubernetes Engine)**: Production deployment
- **Helm**: Kubernetes package management

### Observability
- **KOWL**: Kafka UI for message inspection
- Logging, metrics, and tracing (TBD - specific tools)

## Deployment Architecture

### Development (Minikube)

```
Minikube Cluster
├── Kafka (Strimzi Operator)
│   ├── Kafka Broker
│   └── Zookeeper
├── Payments Service
│   └── Deployment + Service
├── Ledger Service
│   └── Deployment + Service
└── PostgreSQL
    └── StatefulSet + Service
```

### Production (GKE)

```
GKE Cluster
├── Kafka (Strimzi Operator)
│   ├── Kafka Broker (multi-replica)
│   └── Zookeeper (multi-replica)
├── Payments Service
│   ├── Deployment (multiple replicas)
│   ├── Service (LoadBalancer/Ingress)
│   └── HPA (Horizontal Pod Autoscaler)
├── Ledger Service
│   ├── Deployment (multiple replicas)
│   ├── Service (ClusterIP)
│   └── HPA
└── PostgreSQL
    ├── StatefulSet (with persistent volumes)
    └── Service (ClusterIP)
```

## Communication Patterns

### Synchronous Communication

**Payments Service → Ledger Service:**
- Protocol: HTTP/REST
- Pattern: Request-Response
- Use cases:
  - Creating ledger transactions
  - Querying balances
  - Validating operations
- Characteristics:
  - Synchronous (blocks until response)
  - Strong consistency
  - Error handling via HTTP status codes

### Asynchronous Communication

**Payments Service ↔ Kafka:**
- Protocol: Kafka messages
- Pattern: Event-driven
- Use cases:
  - Payment workflow orchestration
  - Retry mechanisms
  - Event notifications
  - Async processing triggers
- Characteristics:
  - Asynchronous (fire-and-forget or eventual consistency)
  - Decoupling between services
  - At-least-once delivery semantics

**External Systems → Payments Service:**
- Protocol: HTTP Webhooks
- Pattern: Push notifications
- Use cases:
  - Stripe payment status updates
  - PSP event notifications
- Characteristics:
  - Async, may arrive out of order
  - Must be idempotent
  - Requires webhook signature validation

## Data Models

### Payment Entity (Payments Service)

```kotlin
Payment {
  id: UUID
  amount: Money
  currency: String
  state: PaymentState (CREATED, CONFIRMING, AUTHORIZED, CAPTURED, REFUNDED, DISPUTED, FAILED)
  idempotencyKey: String
  stripePaymentIntentId: String?
  metadata: Map<String, String>
  createdAt: Timestamp
  updatedAt: Timestamp
}
```

### Ledger Entry (Ledger Service)

```kotlin
LedgerEntry {
  id: UUID
  transactionId: UUID
  account: String
  amount: Money
  type: EntryType (DEBIT, CREDIT)
  idempotencyKey: String
  description: String
  createdAt: Timestamp
}

LedgerTransaction {
  id: UUID
  idempotencyKey: String (unique)
  entries: List<LedgerEntry>
  totalDebits: Money
  totalCredits: Money
  status: TransactionStatus (PENDING, COMMITTED, REJECTED)
  createdAt: Timestamp
}
```

## Security Considerations

### API Security
- Authentication and authorization (TBD - specific mechanism)
- API rate limiting
- Input validation and sanitization

### Webhook Security
- Webhook signature verification (Stripe webhook signatures)
- Idempotency to prevent replay attacks
- Timestamp validation to prevent old webhook processing

### Database Security
- Connection encryption (TLS)
- Credential management (Kubernetes secrets)
- Network policies to restrict access

### Network Security
- Service-to-service communication over internal network
- External APIs exposed via Ingress/LoadBalancer
- Network policies to restrict pod-to-pod communication

## Scalability Considerations

### Payments Service
- Stateless design allows horizontal scaling
- Can scale based on request rate
- Consider rate limiting to prevent overload

### Ledger Service
- Read operations can be scaled horizontally (read replicas)
- Write operations are limited by database write capacity
- SERIALIZABLE isolation may limit throughput (acceptable trade-off for correctness)

### Kafka
- Partition-based scaling
- Consumer groups for parallel processing
- Topic replication for availability

### Database
- PostgreSQL read replicas for balance queries
- Write operations remain on primary (SERIALIZABLE requirement)
- Connection pooling for efficient resource usage

## Failure Modes

See `failure-modes.md` for detailed failure scenarios and handling strategies.

Key failure scenarios:
- Ledger service unavailable
- Database connection failures
- Kafka broker failures
- PSP (Stripe) outages
- Network partitions
- Idempotency key conflicts

## Monitoring & Observability

### Metrics
- Payment creation rate
- Payment state transition rates
- Ledger transaction success/failure rates
- Balance query latency
- API response times
- Error rates by service

### Logging
- Structured logging (JSON format)
- Correlation IDs for request tracing
- Financial operation audit logs
- Error logs with stack traces

### Alerts
- High error rates
- Ledger rejection rates (unbalanced transactions)
- Service unavailability
- Database connection pool exhaustion
- Kafka consumer lag

## Future Considerations

### Potential Enhancements
- Multi-merchant support (would require significant architectural changes)
- Additional PSP integrations
- Payment method expansion
- Advanced reporting and analytics
- Event sourcing for audit trail
- CQRS pattern for read optimization

### Technical Debt Areas
- Observability tooling selection
- Authentication/authorization implementation
- Comprehensive test coverage
- Performance optimization
- Disaster recovery procedures

## Repository Structure

```
payments-platform/
├── services/
│   ├── payments/          # Payments Service code
│   └── ledger/            # Ledger Service code
├── infra/
│   ├── kafka/             # Kafka infrastructure configs
│   ├── postgres/          # PostgreSQL configs
│   ├── minikube/          # Minikube setup
│   └── gke/               # GKE deployment configs
├── helm/
│   ├── payments/          # Helm charts for Payments Service
│   ├── ledger/            # Helm charts for Ledger Service
│   └── kafka/             # Helm charts for Kafka
├── contracts/
│   ├── kafka/             # Kafka message schemas
│   └── api/               # API contracts/specs
└── docs/
    ├── architecture.md    # This file
    ├── invariants.md      # System invariants
    └── failure-modes.md    # Failure handling
```

## References

- [Invariants Documentation](./invariants.md) - System invariants and enforcement
- [Failure Modes Documentation](./failure-modes.md) - Failure scenarios and handling

