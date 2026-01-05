#!/bin/bash

# Example script demonstrating the complete payment flow:
# 1. Register seller Stripe account (if not already registered)
# 2. Create a payment
#
# Usage:
#   ./create-payment-example.sh <buyer_id> <seller_id> <stripe_account_id> <amount_cents> [currency]
#
# Example:
#   ./create-payment-example.sh buyer_123 seller_456 acct_1234567890 10000 USD

set -e

# Configuration
PAYMENTS_SERVICE_URL="${PAYMENTS_SERVICE_URL:-http://localhost:8080}"
INTERNAL_API_TOKEN="${INTERNAL_API_TOKEN:-internal-test-token-change-in-production}"

# Parse arguments
if [ $# -lt 4 ]; then
    echo "Error: Missing required arguments"
    echo "Usage: $0 <buyer_id> <seller_id> <stripe_account_id> <amount_cents> [currency]"
    echo ""
    echo "Arguments:"
    echo "  buyer_id          - The buyer ID (e.g., buyer_123)"
    echo "  seller_id         - The seller ID (e.g., seller_456)"
    echo "  stripe_account_id - The Stripe connected account ID (e.g., acct_1234567890)"
    echo "  amount_cents      - Payment amount in cents (e.g., 10000 for $100.00)"
    echo "  currency          - ISO 4217 currency code (default: USD)"
    exit 1
fi

BUYER_ID="$1"
SELLER_ID="$2"
STRIPE_ACCOUNT_ID="$3"
AMOUNT_CENTS="$4"
CURRENCY="${5:-USD}"

# Validate currency format
if ! [[ "$CURRENCY" =~ ^[A-Z]{3}$ ]]; then
    echo "Error: Currency must be 3 uppercase letters (ISO 4217 format)"
    exit 1
fi

echo "=========================================="
echo "Payment Flow Example"
echo "=========================================="
echo ""

# Step 1: Register seller Stripe account (if not already registered)
echo "Step 1: Registering seller Stripe account..."
REGISTER_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    "$PAYMENTS_SERVICE_URL/internal/sellers/$SELLER_ID/stripe-accounts" \
    -H "Authorization: Bearer $INTERNAL_API_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
        \"stripeAccountId\": \"$STRIPE_ACCOUNT_ID\",
        \"currency\": \"$CURRENCY\"
    }")

REGISTER_HTTP_CODE=$(echo "$REGISTER_RESPONSE" | tail -n1)
REGISTER_BODY=$(echo "$REGISTER_RESPONSE" | sed '$d')

if [ "$REGISTER_HTTP_CODE" -eq 201 ]; then
    echo "✓ Seller Stripe account registered successfully"
elif [ "$REGISTER_HTTP_CODE" -eq 200 ]; then
    echo "✓ Seller Stripe account already exists (updated)"
else
    echo "✗ Failed to register seller Stripe account"
    echo "HTTP Status: $REGISTER_HTTP_CODE"
    echo "$REGISTER_BODY"
    exit 1
fi
echo ""

# Step 2: Create payment
echo "Step 2: Creating payment..."
PAYMENT_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    "$PAYMENTS_SERVICE_URL/payments" \
    -H "Content-Type: application/json" \
    -d "{
        \"buyerId\": \"$BUYER_ID\",
        \"sellerId\": \"$SELLER_ID\",
        \"grossAmountCents\": $AMOUNT_CENTS,
        \"currency\": \"$CURRENCY\",
        \"description\": \"Payment example\"
    }")

PAYMENT_HTTP_CODE=$(echo "$PAYMENT_RESPONSE" | tail -n1)
PAYMENT_BODY=$(echo "$PAYMENT_RESPONSE" | sed '$d')

if [ "$PAYMENT_HTTP_CODE" -eq 201 ]; then
    echo "✓ Payment created successfully"
    echo ""
    echo "Payment Details:"
    echo "$PAYMENT_BODY" | jq '.' 2>/dev/null || echo "$PAYMENT_BODY"
    echo ""
    
    # Extract client_secret for frontend use
    CLIENT_SECRET=$(echo "$PAYMENT_BODY" | jq -r '.clientSecret' 2>/dev/null || echo "")
    if [ -n "$CLIENT_SECRET" ] && [ "$CLIENT_SECRET" != "null" ]; then
        echo "Client Secret (for frontend): $CLIENT_SECRET"
    fi
else
    echo "✗ Failed to create payment"
    echo "HTTP Status: $PAYMENT_HTTP_CODE"
    echo "$PAYMENT_BODY" | jq '.' 2>/dev/null || echo "$PAYMENT_BODY"
    exit 1
fi

echo ""
echo "=========================================="
echo "Payment flow completed successfully!"
echo "=========================================="

