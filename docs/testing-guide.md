# Testing Guide: Payments and Chargebacks

This guide explains how to test various payment and chargeback scenarios using the provided scripts and Stripe CLI.

## Prerequisites

1. **Stripe CLI installed**: `brew install stripe/stripe-cli/stripe`
2. **Stripe API Key**: Set `STRIPE_API_KEY` environment variable
3. **Services running**: Payments and Ledger services must be deployed and accessible
4. **Webhook forwarding**: Stripe CLI must be forwarding webhooks to your local/staging environment

## Basic Payment Testing

### 1. Create a Payment

```bash
./services/payments/scripts/create-payment-example.sh <buyer_id> <seller_id> <amount_cents> [currency]
```

**Example:**
```bash
./services/payments/scripts/create-payment-example.sh buyer_123 seller_456 10000 USD
```

This will:
- Create a payment in `CREATED` state
- Return a `payment_intent_id` and `client_secret`
- **No ledger entries yet** (money hasn't moved)

### 2. Confirm the Payment

```bash
./services/payments/scripts/confirm-payment.sh <payment_intent_id> [client_secret] [card_type]
```

**Example (successful payment):**
```bash
./services/payments/scripts/confirm-payment.sh pi_1234567890 pi_1234567890_secret_abc123
```

**Available card types:**
- `success` (default) - Payment succeeds
- `add_balance` - Funds added to balance (for testing payouts)
- `decline` - Card declined
- `requires_authentication` - Requires 3D Secure
- `chargeback` - Automatically creates a chargeback/dispute
- `inquiry` - Creates retrieval request (inquiry) instead of full dispute

**Expected flow:**
1. Payment state: `CREATED` → `CONFIRMING` → `AUTHORIZED`
2. Webhook: `payment_intent.amount_capturable_updated`
3. Webhook: `payment_intent.succeeded` (if auto-capture enabled)
4. Ledger entries created when payment is `AUTHORIZED` or `CAPTURED`

## Chargeback Testing

### Overview

Chargebacks go through the following states:
- `DISPUTE_CREATED` - Initial dispute created
- `NEEDS_RESPONSE` - Evidence submission required
- `UNDER_REVIEW` - Dispute under review
- Terminal states: `WON`, `LOST`, `WITHDRAWN`, `WARNING_CLOSED`

### Step 1: Create a Chargeback

Use the `chargeback` card type when confirming a payment:

```bash
# First, create a payment
./services/payments/scripts/create-payment-example.sh buyer_123 seller_456 10000 USD

# Then confirm with chargeback card
./services/payments/scripts/confirm-payment.sh <payment_intent_id> <client_secret> chargeback
```

**What happens:**
- Payment is confirmed successfully
- Stripe automatically creates a dispute after a few minutes
- Webhook: `charge.dispute.created` is sent
- Chargeback record created in `DISPUTE_CREATED` state
- Ledger entries: Money moved to `CHARGEBACK_CLEARING` account

**Note:** The dispute may take a few minutes to appear. Check Stripe Dashboard or wait for the webhook.

### Step 2: Advance Chargeback State

Use `test-chargeback.sh` to advance the chargeback through different states:

```bash
./services/payments/scripts/test-chargeback.sh <dispute_id> <action> <status>
```

#### Update to Under Review

```bash
./services/payments/scripts/test-chargeback.sh du_1234567890 update under_review
```

**What happens:**
- Submits evidence to Stripe
- Stripe may transition dispute to `under_review` status
- Webhook: `charge.dispute.updated` (if status changed)
- Chargeback state: `NEEDS_RESPONSE` → `UNDER_REVIEW`

#### Close as Won

```bash
./services/payments/scripts/test-chargeback.sh du_1234567890 close won
```

**What happens:**
- Submits winning evidence: `evidence[uncategorized_text]=winning_evidence`
- Stripe resolves dispute in your favor
- Webhook: `charge.dispute.closed` with status `won`
- Chargeback state: → `WON`
- **Ledger entries:**
  - Money returned to `STRIPE_CLEARING` (disputed amount)
  - **Dispute fee is NOT refunded** (remains as expense)

#### Close as Lost

```bash
./services/payments/scripts/test-chargeback.sh du_1234567890 close lost
```

**What happens:**
- Submits losing evidence: `evidence[uncategorized_text]=losing_evidence`
- Stripe resolves dispute against you
- Webhook: `charge.dispute.closed` with status `lost`
- Chargeback state: → `LOST`
- **Ledger entries:**
  - Debit `SELLER_PAYABLE` (seller loses the amount)
  - Debit `BUYIT_REVENUE` (platform loses revenue share)
  - **Dispute fee is NOT refunded** (remains as expense)

#### Close as Warning Closed

Use the `test-chargeback.sh` script to submit evidence that may trigger `warning_closed`:

```bash
./services/payments/scripts/test-chargeback.sh du_1234567890 close warning_closed
```

**What happens:**
- Submits evidence: `evidence[uncategorized_text]=inquiry_resolution_evidence`
- Stripe processes evidence and may close dispute with `warning_closed` status
- Webhook: `charge.dispute.closed` with status `warning_closed`
- Chargeback state: → `WARNING_CLOSED`
- **Ledger entries:**
  - **BOTH disputed amount AND dispute fee returned** to `STRIPE_CLEARING`
  - Unlike `WON`, the fee is also refunded

**Note:** `warning_closed` is more likely to occur with inquiries (retrieval requests) created using the inquiry test card (`4000000000001976`). For full disputes, you may need to use the Stripe Dashboard to manually close with this status.

#### Close as Withdrawn

For `WITHDRAWN` (buyer cancels dispute), use Stripe Dashboard or API:

```bash
# Option 1: Stripe Dashboard
# Navigate to the dispute and mark it as withdrawn/charge_refunded

# Option 2: Stripe API (test mode only)
curl https://api.stripe.com/v1/disputes/du_1234567890 \
  -u sk_test_YOUR_KEY: \
  -d "status=charge_refunded"
```

**What happens:**
- Buyer withdraws the dispute
- Webhook: `charge.dispute.closed` with status `charge_refunded`
- Chargeback state: → `WITHDRAWN`
- **Ledger entries:**
  - Money returned to `STRIPE_CLEARING` (similar to `WON`)
  - Dispute fee may or may not be refunded (depends on timing)

## Inquiry Testing

### Overview

Inquiries (retrieval requests) are less severe than full disputes. They can be resolved without a formal chargeback.

### Step 1: Create an Inquiry

Use the `inquiry` card type when confirming a payment:

```bash
# First, create a payment
./services/payments/scripts/create-payment-example.sh buyer_123 seller_456 10000 USD

# Then confirm with inquiry card
./services/payments/scripts/confirm-payment.sh <payment_intent_id> <client_secret> inquiry
```

**What happens:**
- Payment is confirmed successfully
- Stripe creates a retrieval request (inquiry) instead of a full dispute
- Webhook: `charge.dispute.created` (but with inquiry type)
- Chargeback record created in `DISPUTE_CREATED` state

**Note:** The inquiry card is `4000000000001976` (not `4242424242423212` as some docs suggest).

### Step 2: Resolve Inquiry

Use the `test-chargeback.sh` script to submit evidence and resolve the inquiry:

```bash
./services/payments/scripts/test-chargeback.sh <dispute_id> close warning_closed
```

**What happens:**
- Submits evidence: `evidence[uncategorized_text]=inquiry_resolution_evidence`
- Stripe processes the evidence
- Inquiry may be closed with `warning_closed` status
- Webhook: `charge.dispute.closed` with status `warning_closed`
- Chargeback state: → `WARNING_CLOSED`
- **Ledger entries:**
  - Both disputed amount and dispute fee returned to `STRIPE_CLEARING`

## Complete Testing Scenarios

### Scenario 1: Successful Payment → Chargeback → Won

```bash
# 1. Create payment
./services/payments/scripts/create-payment-example.sh buyer_123 seller_456 10000 USD

# 2. Confirm with chargeback card
./services/payments/scripts/confirm-payment.sh <pi_id> <client_secret> chargeback

# 3. Wait for dispute to be created (check Stripe Dashboard or logs)
# 4. Get dispute ID from Stripe Dashboard (starts with du_)

# 5. Close as won
./services/payments/scripts/test-chargeback.sh <dispute_id> close won
```

**Expected outcome:**
- Payment: `CAPTURED` → (chargeback created) → `REFUNDED` (if applicable)
- Chargeback: `DISPUTE_CREATED` → `WON`
- Ledger: Money returned to `STRIPE_CLEARING`, dispute fee remains as expense

### Scenario 2: Successful Payment → Chargeback → Lost

```bash
# 1-4. Same as Scenario 1

# 5. Close as lost
./services/payments/scripts/test-chargeback.sh <dispute_id> close lost
```

**Expected outcome:**
- Payment: `CAPTURED` → (chargeback created)
- Chargeback: `DISPUTE_CREATED` → `LOST`
- Ledger: Debit `SELLER_PAYABLE` and `BUYIT_REVENUE`, dispute fee remains as expense

### Scenario 3: Successful Payment → Inquiry → Warning Closed

```bash
# 1. Create payment
./services/payments/scripts/create-payment-example.sh buyer_123 seller_456 10000 USD

# 2. Confirm with inquiry card
./services/payments/scripts/confirm-payment.sh <pi_id> <client_secret> inquiry

# 3. Wait for inquiry to be created
# 4. Get dispute ID from Stripe Dashboard

# 5. Submit evidence to close inquiry
./services/payments/scripts/test-chargeback.sh <dispute_id> close warning_closed
```

**Expected outcome:**
- Payment: `CAPTURED` → (inquiry created)
- Chargeback: `DISPUTE_CREATED` → `WARNING_CLOSED`
- Ledger: Both disputed amount and dispute fee returned to `STRIPE_CLEARING`

## Webhook Events Reference

### Payment Webhooks

| Event | When | State Transition |
|-------|------|------------------|
| `payment_intent.created` | Payment created | `CREATED` |
| `payment_intent.amount_capturable_updated` | Payment authorized | `CONFIRMING` → `AUTHORIZED` |
| `payment_intent.succeeded` | Payment captured | `AUTHORIZED` → `CAPTURED` |
| `payment_intent.payment_failed` | Payment failed | → `FAILED` |

### Chargeback Webhooks

| Event | When | State Transition |
|-------|------|------------------|
| `charge.dispute.created` | Dispute created | → `DISPUTE_CREATED` |
| `charge.dispute.updated` | Dispute status changed | `NEEDS_RESPONSE` → `UNDER_REVIEW` |
| `charge.dispute.closed` | Dispute resolved | → `WON` / `LOST` / `WITHDRAWN` / `WARNING_CLOSED` |

### Refund Webhooks

| Event | When | State Transition |
|-------|------|------------------|
| `refund.created` | Refund initiated | `CAPTURED` → `REFUNDING` |
| `refund.updated` | Refund status changed | `REFUNDING` → `REFUNDED` |

## Troubleshooting

### Dispute Not Created

- **Issue:** Chargeback card used but no dispute appears
- **Solution:** 
  - Wait 5-10 minutes (Stripe creates disputes asynchronously)
  - Check Stripe Dashboard → Disputes
  - Verify webhook forwarding is active

### Webhook Not Received

- **Issue:** Script executed but no webhook received
- **Solution:**
  - Verify Stripe CLI is forwarding: `stripe listen --forward-to http://localhost:8080/webhooks/stripe`
  - Check webhook endpoint is accessible
  - Verify webhook signature validation is working

### State Transition Errors

- **Issue:** "Invalid state transition" errors
- **Solution:**
  - Check current state in database
  - Verify state machine allows the transition
  - Some states can transition directly (e.g., `NEEDS_RESPONSE` → `WON`)

### Ledger Entries Not Created

- **Issue:** Webhook processed but no ledger entries
- **Solution:**
  - Check ledger service logs
  - Verify Kafka event was published
  - Check ledger service consumer is running
  - Verify idempotency keys are unique

## Quick Reference

### Test Cards

| Card Number | Type | Behavior |
|-------------|------|----------|
| `4242424242424242` | Success | Payment succeeds |
| `4000000000000077` | Add Balance | Funds added to available balance |
| `4000000000000259` | Chargeback | Creates full dispute |
| `4000000000001976` | Inquiry | Creates retrieval request |
| `4000000000000002` | Decline | Card declined |

### Scripts

| Script | Purpose |
|--------|---------|
| `create-payment-example.sh` | Create a payment |
| `confirm-payment.sh` | Confirm payment with test card |
| `test-chargeback.sh` | Advance chargeback state |

### Stripe CLI Commands

```bash
# Forward webhooks
stripe listen --forward-to http://localhost:8080/webhooks/stripe

# Update dispute evidence
stripe disputes update du_1234567890 -d "evidence[uncategorized_text]=evidence"

# List disputes
stripe disputes list

# Get dispute details
stripe disputes retrieve du_1234567890
```

## Additional Resources

- [Stripe Testing Documentation](https://stripe.com/docs/testing)
- [Stripe Disputes Guide](https://stripe.com/docs/disputes)
- [Stripe CLI Documentation](https://stripe.com/docs/stripe-cli)

