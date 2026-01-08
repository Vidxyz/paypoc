# BuyIt - E-Commerce Platform

**BuyIt** is a modern, scalable e-commerce platform designed to handle the complete shopping experience from product discovery to payment processing. This repository contains the full-stack implementation, starting with a production-ready payments platform that serves as the foundation for the broader marketplace.

## 🎯 Vision

BuyIt aims to be a comprehensive e-commerce platform that enables seamless transactions between buyers and sellers while providing a delightful shopping experience. The platform is built with a microservices architecture, ensuring scalability, maintainability, and clear separation of concerns.

## 📋 Current State: Payments Platform

The current implementation focuses on **payments infrastructure** - a robust, production-ready payment processing system that handles:

- **Payment Processing**: Create, authorize, capture, and refund payments via Stripe
- **Financial Ledger**: Double-entry bookkeeping with strict financial correctness guarantees
- **Marketplace Splits**: Automatic calculation and distribution of platform fees (10%) and seller payouts
- **Refunds**: Full refund workflow with state management
- **Webhook Handling**: Reliable processing of Stripe webhooks for payment status updates
- **Frontend Application**: React-based web interface for payment creation and management

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                      Frontend (React)                        │
│  - Payment creation and checkout                            │
│  - Payment history and refund management                    │
│  - Material-UI based responsive interface                   │
└──────────────────┬──────────────────────────────────────────┘
                   │ HTTP/REST
                   │
┌──────────────────▼──────────────────────────────────────────┐
│                  Payments Service (Kotlin/Spring Boot)      │
│  - Payment orchestration and workflow management            │
│  - Stripe integration (PaymentIntents, Refunds)             │
│  - Public REST APIs                                         │
│  - Webhook handling and event processing                    │
│  - State machine enforcement                                │
└────────┬───────────────────────────┬────────────────────────┘
         │                           │
         │ Sync HTTP                 │ Async Kafka
         │                           │
         ▼                           ▼
┌──────────────────┐        ┌──────────────────────┐
│ Ledger Service   │        │   Kafka (Strimzi)    │
│ (Kotlin/Spring)  │        │  - Event streaming   │
│                  │        │  - Workflow retries  │
│ - Financial truth│        │  - Event sourcing    │
│ - Double-entry   │        └──────────────────────┘
│ - Balance calc   │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  PostgreSQL      │
│  (SERIALIZABLE)  │
└──────────────────┘
```

### Core Principles

1. **Ledger as Source of Truth**: All financial data is maintained in the Ledger Service using double-entry bookkeeping
2. **Orchestration vs. Truth**: Payments Service orchestrates workflows; Ledger Service maintains financial correctness
3. **Event-Driven Architecture**: Kafka enables async processing, retries, and loose coupling
4. **Money Movement First**: Ledger writes occur only after Stripe confirms actual money movement via webhooks
5. **Idempotency Everywhere**: All operations are idempotent to handle retries and failures gracefully

### Key Features

- ✅ **Payment Processing**: Full payment lifecycle (CREATED → CONFIRMING → AUTHORIZED → CAPTURED)
- ✅ **Refunds**: Complete refund workflow with state transitions (CAPTURED → REFUNDING → REFUNDED)
- ✅ **Marketplace Split**: Automatic 10% platform fee calculation and seller payout
- ✅ **Payouts**: Manual payout system for transferring seller funds (PENDING → PROCESSING → COMPLETED/FAILED)
- ✅ **Webhook Reliability**: Idempotent webhook processing with signature verification
- ✅ **Financial Correctness**: Double-entry bookkeeping with SERIALIZABLE isolation
- ✅ **Frontend UI**: React-based interface for buyers to create payments and view history
- ✅ **Authentication**: Bearer token-based authentication for user context
- ✅ **Multi-Currency**: Support for USD, CAD, EUR (extensible to more currencies)

### Technology Stack

**Backend:**
- Kotlin + Spring Boot
- PostgreSQL (with SERIALIZABLE isolation for ledger)
- Kafka (Strimzi Operator) for event streaming
- Stripe SDK for payment processing

**Frontend:**
- React 18 with Vite
- Material-UI (MUI) for components and theming
- React Router for navigation
- Stripe.js for secure card input

**Infrastructure:**
- Kubernetes (Minikube for local, GKE-ready)
- Docker for containerization
- Nginx for frontend serving and API proxying
- Terraform for infrastructure as code (Minikube)

## 🚀 Quick Start

### Prerequisites

- Docker & Docker Compose
- Minikube (for local Kubernetes)
- kubectl
- Stripe account with API keys
- Terraform (for infrastructure provisioning)

### Local Development Setup

1. **Start Minikube**:
   ```bash
   minikube start
   ```

2. **Deploy Infrastructure** (Kafka, PostgreSQL):
   ```bash
   cd infra/minikube
   terraform init
   terraform apply
   ```

3. **Configure Secrets**:
   ```bash
   # Set Stripe keys as environment variables
   export STRIPE_SECRET_KEY=sk_test_...
   export STRIPE_WEBHOOK_SECRET=whsec_...
   export STRIPE_PUBLISHABLE_KEY=pk_test_...
   
   # Generate Kubernetes secrets
   cd kubernetes/payments
   # Edit secret.yaml with your Stripe keys
   kubectl apply -f secret.yaml
   ```

4. **Register Seller Stripe Account**:
   ```bash
   # Register a seller's Stripe Connect account
   cd services/payments/scripts
   ./register-seller-account.sh <sellerId> <currency>
   ```

5. **Deploy Services**:
   ```bash
   # Build and deploy all services
   ./scripts/deploy.sh
   ```

6. **Access the Application**:
   ```bash
   # Add ingress to /etc/hosts
   echo "$(minikube ip) buyit.local payments.local" | sudo tee -a /etc/hosts
   
   # Access frontend
   open http://buyit.local
   
   # Default credentials: buyer123 / buyer123
   ```

For detailed deployment instructions, see:
- [Deployment Guide](scripts/DEPLOYMENT.md)
- [Kubernetes README](kubernetes/README.md)

## 📦 Services

### Payments Service
Payment orchestration layer that coordinates payment workflows with Stripe and manages payment state.

**Location**: `services/payments/`

**Key Responsibilities**:
- Create PaymentIntents via Stripe
- Handle Stripe webhooks (payment status updates)
- Manage payment state machine
- Process refunds
- Manage payouts to sellers
- Integrate with Ledger Service for financial recording

**Documentation**: [services/payments/README.md](services/payments/README.md)

### Ledger Service
Financial system of record that maintains all transactions using double-entry bookkeeping.

**Location**: `services/ledger/`

**Key Responsibilities**:
- Maintain append-only ledger of all financial transactions
- Calculate and serve account balances
- Enforce double-entry bookkeeping (balanced transactions)
- Ensure financial correctness with SERIALIZABLE isolation

**Documentation**: [services/ledger/README.md](services/ledger/README.md)

### Frontend Service
React-based web application for buyers to create payments and view payment history.

**Location**: `services/frontend/`

**Key Features**:
- Login/Authentication
- Payment creation and checkout
- Payment history viewing
- Refund initiation

**Documentation**: [services/frontend/README.md](services/frontend/README.md)

## 💰 Payout Flow

The platform uses a **Separate Charges and Transfers** model with Stripe Connect:

### Money Flow Overview

1. **Payment Capture**: When a buyer pays, all funds are collected into the platform's Stripe account
2. **Ledger Recording**: The ledger records the transaction, crediting `SELLER_PAYABLE` (a liability account representing money owed to the seller)
3. **Payout Initiation**: Sellers can request payouts via API, which creates a Stripe Transfer
4. **Transfer Completion**: When Stripe confirms the transfer is complete, the ledger debits `SELLER_PAYABLE` and credits `STRIPE_CLEARING`

### Payout State Machine

Payouts follow a strict state machine with the following transitions:

```
PENDING → PROCESSING → COMPLETED
         ↓
       FAILED
```

- **PENDING**: Payout created but not yet initiated with Stripe (not currently used - payouts start in PROCESSING)
- **PROCESSING**: Stripe Transfer created, waiting for completion webhook
- **COMPLETED**: Transfer completed successfully, ledger updated
- **FAILED**: Transfer failed (terminal state)

### Payout API Endpoints

**Create Manual Payout**:
```http
POST /api/payouts
Content-Type: application/json

{
  "sellerId": "seller123",
  "amountCents": 10000,
  "currency": "usd",
  "description": "Monthly payout"
}
```

**Payout All Pending Funds**:
```http
POST /api/sellers/{sellerId}/payouts
Content-Type: application/json

{
  "currency": "usd"
}
```

**Get Payout Details**:
```http
GET /api/payouts/{payoutId}
```

**List Seller Payouts**:
```http
GET /api/sellers/{sellerId}/payouts
```

### Payout Workflow

1. **Payout Creation** (`POST /api/payouts` or `POST /api/sellers/{sellerId}/payouts`):
   - Validates seller has Stripe Connect account configured
   - Checks ledger for available balance (for pending funds endpoint)
   - Creates Stripe Transfer via Stripe API
   - Persists payout in `PROCESSING` state
   - **No ledger write yet** - money hasn't moved

2. **Stripe Transfer Webhooks**:
   - `transfer.created`: Transfer initiated (logged for tracking)
   - `transfer.paid`: Transfer completed successfully
     - Transitions payout to `COMPLETED`
     - Publishes `PayoutCompletedEvent` to Kafka
   - `transfer.failed`: Transfer failed
     - Transitions payout to `FAILED`
     - Records failure reason

3. **Ledger Update** (via Kafka consumer):
   - `PayoutCompletedEventConsumer` processes the event
   - Creates ledger transaction:
     - **DR** `SELLER_PAYABLE` (reduces liability)
     - **CR** `STRIPE_CLEARING` (money moved to seller's Stripe account)

### Key Principles

- **Ledger writes only after Stripe confirms**: The ledger is only updated when Stripe webhook confirms the transfer is complete
- **Idempotency**: All payout operations are idempotent using unique idempotency keys
- **State Machine Enforcement**: Payout state transitions are enforced by `PayoutStateMachine`, preventing invalid transitions
- **Separation of Concerns**: Payments Service orchestrates payouts; Ledger Service maintains financial truth

## 🔮 Future State: Full E-Commerce Platform

The payments platform is the foundation for a complete e-commerce marketplace. Future development will expand BuyIt into a comprehensive shopping platform with the following capabilities:

### 🛍️ Product Catalog
- **Product Management**: CRUD operations for products (create, read, update, delete)
- **Product Categories**: Hierarchical category system for product organization
- **Product Attributes**: Variants, SKUs, specifications, and custom attributes
- **Media Management**: Image and video uploads for product listings
- **Seller Product Management**: Multi-vendor support with seller-specific product listings

### 📦 Inventory Management
- **Stock Tracking**: Real-time inventory levels with SKU-level granularity
- **Warehouse Management**: Multi-warehouse inventory allocation
- **Low Stock Alerts**: Automated notifications for inventory thresholds
- **Inventory Reservations**: Hold inventory during checkout process
- **Stock Reconciliation**: Automated inventory audits and corrections

### 🛒 Shopping Cart & Checkout
- **Shopping Cart**: Persistent cart across sessions with item management
- **Cart Abandonment**: Tracking and recovery workflows
- **Multi-Item Checkout**: Process multiple items in a single transaction
- **Shipping Calculation**: Real-time shipping cost calculation based on address
- **Tax Calculation**: Automated tax computation based on location and product type
- **Promo Codes & Discounts**: Coupon system with percentage/fixed discounts

### 📋 Order Management
- **Order Processing**: Complete order lifecycle (PENDING → CONFIRMED → PROCESSING → SHIPPED → DELIVERED)
- **Order History**: Comprehensive order tracking for buyers and sellers
- **Order Cancellation**: Cancel orders with automatic refund processing
- **Partial Fulfillment**: Support for split shipments and partial orders
- **Order Status Notifications**: Email/SMS notifications for order updates

### 🔍 Search & Discovery
- **Full-Text Search**: Elasticsearch integration for fast product search
- **Filters & Facets**: Category, price, brand, ratings, and custom filters
- **Search Ranking**: Relevance-based product ranking algorithms
- **Autocomplete**: Real-time search suggestions as users type
- **Search Analytics**: Track popular searches and optimize results

### 💡 Recommendations & Personalization
- **Product Recommendations**: "You may also like" based on purchase history
- **Personalized Homepage**: Customized product feeds based on user preferences
- **Trending Products**: Algorithm-driven trending product discovery
- **Recently Viewed**: Track and display recently viewed items
- **Collaborative Filtering**: "Customers who bought this also bought" recommendations

### 👤 User Management & Profiles
- **User Accounts**: Registration, authentication, and profile management
- **Address Book**: Multiple shipping and billing addresses
- **Payment Methods**: Saved payment methods for faster checkout
- **Order Preferences**: Default shipping options and notification preferences
- **Wishlists**: Save products for later purchase

### 🏪 Seller Portal
- **Seller Dashboard**: Analytics, sales reports, and performance metrics
- **Product Management Interface**: Easy-to-use product creation and editing
- **Order Management**: View and process orders from buyers
- **Inventory Dashboard**: Stock levels and replenishment alerts
- **Financial Dashboard**: Payout history, earnings, and fee breakdown
- **Seller Analytics**: Sales trends, popular products, and customer insights

### 📊 Analytics & Reporting
- **Sales Analytics**: Revenue, conversion rates, and sales trends
- **Product Analytics**: Best sellers, slow movers, and inventory turnover
- **Customer Analytics**: Customer lifetime value, retention, and segmentation
- **Financial Reports**: P&L statements, tax reports, and reconciliation
- **Operational Metrics**: Order processing time, shipping performance, and customer satisfaction

### 🚚 Shipping & Fulfillment
- **Shipping Providers**: Integration with carriers (FedEx, UPS, DHL, etc.)
- **Label Generation**: Automated shipping label creation
- **Tracking Integration**: Real-time package tracking updates
- **Shipping Zones**: Configure shipping rates by region
- **Delivery Options**: Express, standard, and local delivery options

### 💬 Reviews & Ratings
- **Product Reviews**: Customer reviews with text and ratings
- **Seller Ratings**: Overall seller performance ratings
- **Review Moderation**: Spam detection and content moderation
- **Review Aggregation**: Average ratings and review summaries
- **Photo Reviews**: Allow customers to attach photos to reviews

### 🔔 Notifications
- **Email Notifications**: Order confirmations, shipping updates, promotions
- **SMS Notifications**: Order status and delivery updates (opt-in)
- **Push Notifications**: Browser and mobile app push notifications
- **In-App Notifications**: Real-time notifications within the platform
- **Notification Preferences**: User-configurable notification settings

### 🌍 Multi-Language & Localization
- **Internationalization (i18n)**: Support for multiple languages
- **Currency Conversion**: Real-time currency conversion for global markets
- **Regional Pricing**: Market-specific pricing strategies
- **Local Payment Methods**: Support for region-specific payment methods

### 🔐 Security & Compliance
- **Compliance**: GDPR, CCPA, and regional tax compliance
- **PCI Compliance**: Secure handling of payment card data
- **Data Encryption**: Encryption at rest and in transit
- **Fraud Detection**: ML-based fraud detection and prevention
- **Rate Limiting**: API rate limiting and abuse prevention
- **Audit Logging**: Comprehensive audit trails for all operations

## 📁 Project Structure

```
payments-platform/
├── services/              # Microservices
│   ├── payments/         # Payment orchestration service
│   ├── ledger/           # Financial ledger service
│   └── frontend/         # React frontend application
├── kubernetes/           # Kubernetes manifests
│   ├── payments/        # Payments service deployment
│   ├── ledger/          # Ledger service deployment
│   ├── frontend/        # Frontend deployment
│   └── README.md        # Deployment guide
├── infra/               # Infrastructure as code
│   └── minikube/        # Terraform for local K8s
├── scripts/             # Deployment and utility scripts
├── docs/                # Architecture and design docs
│   ├── architecture.md  # Detailed architecture
│   └── invariants.md    # Financial invariants
└── contracts/           # API and Kafka contracts
    ├── api/             # REST API contracts
    └── kafka/           # Kafka message contracts
```

## 📚 Documentation

- [Architecture Documentation](docs/architecture.md) - Detailed system architecture
- [Financial Invariants](docs/invariants.md) - Core financial correctness guarantees
- [Deployment Guide](scripts/DEPLOYMENT.md) - Step-by-step deployment instructions
- [Kubernetes Setup](kubernetes/README.md) - Kubernetes deployment details
- [Payments Service](services/payments/README.md) - Payments service documentation
- [Ledger Service](services/ledger/README.md) - Ledger service documentation
- [Frontend Service](services/frontend/README.md) - Frontend application documentation

## 🤝 Contributing

This is a development project. For contributions:

1. Follow the existing code style and architecture patterns
2. Ensure all tests pass
3. Update documentation for any API or behavior changes
4. Maintain financial invariants when modifying ledger or payment logic

## 📄 License

[Add your license here]

## 🔗 External Services

- **Stripe**: Payment processing and marketplace split payments
- **PostgreSQL**: Primary database for all services
- **Kafka (Strimzi)**: Event streaming and async processing

---

**Note**: This platform is currently in active development. The payments infrastructure is production-ready, while other features are planned for future iterations.

