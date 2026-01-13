#!/bin/bash

# Script to register a seller Stripe account via the Payments Service internal API
# 
# Usage:
#   ./register-seller-account.sh <seller_id> <stripe_account_id> [currency]
#
# Example:
#   ./register-seller-account.sh seller_123 acct_1234567890 USD
#
# Environment variables:
#   PAYMENTS_SERVICE_URL - Base URL of the Payments Service (default: http://localhost:8080)
#   INTERNAL_API_TOKEN - Internal API token for authentication (default: internal-test-token-change-in-production)

set -e

# Configuration
PAYMENTS_SERVICE_URL="${PAYMENTS_SERVICE_URL:-http://payments.local}"
INTERNAL_API_TOKEN="${INTERNAL_API_TOKEN:-internal-test-token-change-in-production}"

# Parse arguments
if [ $# -lt 2 ]; then
    echo "Error: Missing required arguments"
    echo "Usage: $0 <seller_id> <stripe_account_id> [currency]"
    echo ""
    echo "Arguments:"
    echo "  seller_id         - The seller ID (e.g., seller_123)"
    echo "  stripe_account_id - The Stripe connected account ID (e.g., acct_1234567890)"
    echo "  currency          - ISO 4217 currency code (default: CAD)"
    echo ""
    echo "Environment variables:"
    echo "  PAYMENTS_SERVICE_URL - Payments Service URL (default: http://localhost:8080)"
    echo "  INTERNAL_API_TOKEN   - Internal API token (default: internal-test-token-change-in-production)"
    exit 1
fi

SELLER_ID="$1"
STRIPE_ACCOUNT_ID="$2"
CURRENCY="${3:-CAD}"

# Validate currency format (3 uppercase letters)
if ! [[ "$CURRENCY" =~ ^[A-Z]{3}$ ]]; then
    echo "Error: Currency must be 3 uppercase letters (ISO 4217 format)"
    echo "Example: USD, EUR, GBP"
    exit 1
fi

# Register seller Stripe account
echo "Registering seller Stripe account..."
echo "  Seller ID: $SELLER_ID"
echo "  Stripe Account ID: $STRIPE_ACCOUNT_ID"
echo "  Currency: $CURRENCY"
echo ""

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    "$PAYMENTS_SERVICE_URL/internal/sellers/$SELLER_ID/stripe-accounts" \
    -H "Authorization: Bearer $INTERNAL_API_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
        \"stripeAccountId\": \"$STRIPE_ACCOUNT_ID\",
        \"currency\": \"$CURRENCY\"
    }")

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" -eq 201 ] || [ "$HTTP_CODE" -eq 200 ]; then
    echo "✓ Successfully registered seller Stripe account"
    echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"
    exit 0
else
    echo "✗ Failed to register seller Stripe account"
    echo "HTTP Status: $HTTP_CODE"
    echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"
    exit 1
fi

