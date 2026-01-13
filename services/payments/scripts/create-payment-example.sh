#!/bin/bash

# Example script demonstrating the payment creation flow:
# Creates a payment where money goes to the BuyIt platform account.
# The platform will later handle payouts to sellers via Stripe Transfers.
#
# Usage:
#   ./create-payment-example.sh <buyer_id> <seller_id> <amount_cents> [currency]
#
# Example:
#   ./create-payment-example.sh buyer_123 seller_456 10000 USD

set -e

# Configuration
PAYMENTS_SERVICE_URL="${PAYMENTS_SERVICE_URL:-http://payments.local}"

# Parse arguments
if [ $# -lt 3 ]; then
    echo "Error: Missing required arguments"
    echo "Usage: $0 <buyer_id> <seller_id> <amount_cents> [currency]"
    echo ""
    echo "Arguments:"
    echo "  buyer_id     - The buyer ID (e.g., buyer_123)"
    echo "  seller_id    - The seller ID (e.g., seller_456)"
    echo "  amount_cents - Payment amount in cents (e.g., 10000 for $100.00)"
    echo "  currency     - ISO 4217 currency code (default: CAD)"
    echo ""
    echo "Note: Payments go to the BuyIt platform account. Seller Stripe account"
    echo "      registration is not required for payment creation. Sellers will"
    echo "      receive payouts later via the payout API."
    exit 1
fi

BUYER_ID="$1"
SELLER_ID="$2"
AMOUNT_CENTS="$3"
CURRENCY="${4:-CAD}"

# Validate currency format
if ! [[ "$CURRENCY" =~ ^[A-Z]{3}$ ]]; then
    echo "Error: Currency must be 3 uppercase letters (ISO 4217 format)"
    exit 1
fi

echo "=========================================="
echo "Payment Creation Example"
echo "=========================================="
echo "Buyer ID: $BUYER_ID"
echo "Seller ID: $SELLER_ID"
echo "Amount: $AMOUNT_CENTS cents ($CURRENCY)"
echo ""

# Create payment (money goes to platform account)
echo "Creating payment..."
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
    
    # Extract payment details
    PAYMENT_ID=$(echo "$PAYMENT_BODY" | jq -r '.id' 2>/dev/null || echo "")
    CLIENT_SECRET=$(echo "$PAYMENT_BODY" | jq -r '.clientSecret' 2>/dev/null || echo "")
    STRIPE_PAYMENT_INTENT_ID=$(echo "$PAYMENT_BODY" | jq -r '.stripePaymentIntentId' 2>/dev/null || echo "")
    
    if [ -n "$CLIENT_SECRET" ] && [ "$CLIENT_SECRET" != "null" ]; then
        echo "Client Secret (for frontend): $CLIENT_SECRET"
    fi
    
    if [ -n "$STRIPE_PAYMENT_INTENT_ID" ] && [ "$STRIPE_PAYMENT_INTENT_ID" != "null" ]; then
        echo ""
        echo "Next steps:"
        echo "  1. Use the client_secret in your frontend to confirm the payment"
        echo "  2. Or use the confirm-payment.sh script:"
        echo "     ./confirm-payment.sh $STRIPE_PAYMENT_INTENT_ID $CLIENT_SECRET"
        echo ""
        echo "     To test chargebacks/disputes:"
        echo "     ./confirm-payment.sh $STRIPE_PAYMENT_INTENT_ID $CLIENT_SECRET chargeback"
        echo ""
        echo "     To test inquiries (retrieval requests):"
        echo "     ./confirm-payment.sh $STRIPE_PAYMENT_INTENT_ID $CLIENT_SECRET inquiry"
        echo ""
        echo "Note: Money is collected to the BuyIt platform account."
        echo "      Sellers will receive payouts via the payout API later."
    fi
else
    echo "✗ Failed to create payment"
    echo "HTTP Status: $PAYMENT_HTTP_CODE"
    echo "$PAYMENT_BODY" | jq '.' 2>/dev/null || echo "$PAYMENT_BODY"
    exit 1
fi

echo ""
echo "=========================================="
echo "Payment creation completed successfully!"
echo "=========================================="

