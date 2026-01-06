#!/bin/bash

# Script to confirm a Stripe PaymentIntent using Stripe API
# This simulates a buyer confirming payment with a test card
#
# Usage:
#   ./confirm-payment.sh <payment_intent_id> [client_secret]
#
# Example:
#   ./confirm-payment.sh pi_1234567890 pi_1234567890_secret_abc123
#
# Environment variables:
#   STRIPE_API_KEY - Stripe API key (required, e.g., sk_test_...)

set -e

# Configuration
STRIPE_API_KEY="${STRIPE_API_KEY:-}"

# Test card details (Stripe test card for successful payment)
TEST_CARD_NUMBER="4242424242424242"
TEST_CARD_EXP_MONTH="12"
TEST_CARD_EXP_YEAR="2025"
TEST_CARD_CVC="123"

# Parse arguments
if [ $# -lt 1 ]; then
    echo "Error: Missing required arguments"
    echo "Usage: $0 <payment_intent_id> [client_secret]"
    echo ""
    echo "Arguments:"
    echo "  payment_intent_id - The Stripe PaymentIntent ID (e.g., pi_1234567890)"
    echo "  client_secret     - Optional: The client secret for the PaymentIntent"
    echo ""
    echo "Environment variables:"
    echo "  STRIPE_API_KEY    - Stripe API key (required, e.g., sk_test_...)"
    echo ""
    echo "Example:"
    echo "  ./confirm-payment.sh pi_1234567890"
    echo "  ./confirm-payment.sh pi_1234567890 pi_1234567890_secret_abc123"
    exit 1
fi

PAYMENT_INTENT_ID="$1"
CLIENT_SECRET="${2:-}"

# Validate PaymentIntent ID format
if ! [[ "$PAYMENT_INTENT_ID" =~ ^pi_ ]]; then
    echo "Error: PaymentIntent ID must start with 'pi_'"
    echo "Example: pi_1234567890"
    exit 1
fi

echo "=========================================="
echo "Confirming Stripe PaymentIntent"
echo "=========================================="
echo "PaymentIntent ID: $PAYMENT_INTENT_ID"
if [ -n "$CLIENT_SECRET" ]; then
    echo "Client Secret: ${CLIENT_SECRET:0:20}..."
fi
echo ""

# Get Stripe API key (required)
if [ -z "$STRIPE_API_KEY" ]; then
    # Try to get from Stripe CLI config if available
    if command -v stripe &> /dev/null; then
        STRIPE_API_KEY=$(stripe config --get test_mode_api_key 2>/dev/null || echo "")
    fi
    
    if [ -z "$STRIPE_API_KEY" ]; then
        echo "Error: STRIPE_API_KEY environment variable is required"
        echo ""
        echo "Set it with:"
        echo "  export STRIPE_API_KEY=sk_test_..."
        echo ""
        echo "Or pass it when running the script:"
        echo "  STRIPE_API_KEY=sk_test_... ./confirm-payment.sh $PAYMENT_INTENT_ID"
        exit 1
    fi
fi

echo "Confirming payment with test card..."
echo "Card: $TEST_CARD_NUMBER (Visa test card - will succeed)"
echo "Expiry: $TEST_CARD_EXP_MONTH/$TEST_CARD_EXP_YEAR"
echo ""

# Step 1: Create a payment method with the test card
echo "Step 1: Creating payment method with test card..."
PAYMENT_METHOD_RESPONSE=$(curl -s -X POST https://api.stripe.com/v1/payment_methods \
    -u "$STRIPE_API_KEY:" \
    -d "type=card" \
    -d "card[number]=$TEST_CARD_NUMBER" \
    -d "card[exp_month]=$TEST_CARD_EXP_MONTH" \
    -d "card[exp_year]=$TEST_CARD_EXP_YEAR" \
    -d "card[cvc]=$TEST_CARD_CVC")

PAYMENT_METHOD_ID=$(echo "$PAYMENT_METHOD_RESPONSE" | grep -o '"id":"pm_[^"]*"' | cut -d'"' -f4)

if [ -z "$PAYMENT_METHOD_ID" ]; then
    echo "✗ Failed to create payment method"
    echo "$PAYMENT_METHOD_RESPONSE" | grep -o '"message":"[^"]*"' | cut -d'"' -f4 || echo "$PAYMENT_METHOD_RESPONSE"
    exit 1
fi

echo "✓ Created payment method: $PAYMENT_METHOD_ID"
echo ""

# Step 2: Confirm the PaymentIntent with the payment method
echo "Step 2: Confirming PaymentIntent..."
CONFIRM_DATA="payment_method=$PAYMENT_METHOD_ID"

# Add client secret if provided
if [ -n "$CLIENT_SECRET" ]; then
    CONFIRM_DATA="$CONFIRM_DATA&client_secret=$CLIENT_SECRET"
fi

CONFIRM_RESPONSE=$(curl -s -X POST "https://api.stripe.com/v1/payment_intents/$PAYMENT_INTENT_ID/confirm" \
    -u "$STRIPE_API_KEY:" \
    -d "$CONFIRM_DATA")

# Check if confirmation was successful
if echo "$CONFIRM_RESPONSE" | grep -q '"status":"succeeded"\|"status":"requires_capture"'; then
    echo ""
    echo "=========================================="
    echo "✓ Payment confirmed successfully!"
    echo "=========================================="
    echo ""
    
    # Extract status from response
    STATUS=$(echo "$CONFIRM_RESPONSE" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
    echo "PaymentIntent Status: $STATUS"
    echo ""
    
    if [ "$STATUS" = "requires_capture" ]; then
        echo "Payment is authorized and ready to capture."
        echo "You should receive webhook events:"
        echo "  - payment_intent.amount_capturable_updated"
        echo "  - payment_intent.succeeded (after capture)"
    elif [ "$STATUS" = "succeeded" ]; then
        echo "Payment is already captured and succeeded."
        echo "You should receive webhook event: payment_intent.succeeded"
    fi
    echo ""
    echo "PaymentIntent Details:"
    echo "$CONFIRM_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$CONFIRM_RESPONSE"
    exit 0
else
    echo ""
    echo "=========================================="
    echo "✗ Failed to confirm payment"
    echo "=========================================="
    echo ""
    
    # Extract error message
    ERROR_MSG=$(echo "$CONFIRM_RESPONSE" | grep -o '"message":"[^"]*"' | cut -d'"' -f4)
    if [ -n "$ERROR_MSG" ]; then
        echo "Error: $ERROR_MSG"
        echo ""
    fi
    
    echo "Possible reasons:"
    echo "  - PaymentIntent doesn't exist"
    echo "  - PaymentIntent is already confirmed"
    echo "  - PaymentIntent is in an invalid state"
    echo "  - Stripe API key is invalid or not set"
    echo "  - Payment method creation failed"
    echo ""
    echo "Full response:"
    echo "$CONFIRM_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$CONFIRM_RESPONSE"
    exit 1
fi

