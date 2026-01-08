#!/bin/bash

# Script to confirm a Stripe PaymentIntent using Stripe API
# This simulates a buyer confirming payment with a test card
#
# Usage:
#   ./confirm-payment.sh <payment_intent_id> [client_secret] [card_type]
#
# Example:
#   ./confirm-payment.sh pi_1234567890
#   ./confirm-payment.sh pi_1234567890 pi_1234567890_secret_abc123
#   ./confirm-payment.sh pi_1234567890 "" decline
#
# Environment variables:
#   STRIPE_API_KEY - Stripe API key (required, e.g., sk_test_...)

set -e

# Configuration
STRIPE_API_KEY="${STRIPE_API_KEY:-}"

# Function to get test card details based on card type
get_test_card() {
    local card_type="${1:-success}"
    
    case "$card_type" in
        success)
            echo "4242424242424242|12|2028|123|Visa - Success"
            ;;
        add_balance)
            echo "4000000000000077|12|2028|123|Visa - Funds added to balance"
            ;;
        decline)
            echo "4000000000000002|12|2028|123|Visa - Card declined"
            ;;
        insufficient_funds)
            echo "4000000000009995|12|2028|123|Visa - Insufficient funds"
            ;;
        lost_card)
            echo "4000000000009987|12|2028|123|Visa - Lost card"
            ;;
        stolen_card)
            echo "4000000000009979|12|2028|123|Visa - Stolen card"
            ;;
        requires_authentication|3ds)
            echo "4000002500003155|12|2028|123|Visa - Requires 3D Secure authentication"
            ;;
        requires_payment_method)
            echo "4000000000000341|12|2028|123|Visa - Requires payment method"
            ;;
        processing_error)
            echo "4000000000000119|12|2028|123|Visa - Processing error"
            ;;
        *)
            echo "Error: Unknown card type: $card_type" >&2
            echo "" >&2
            echo "Available card types:" >&2
            echo "  success              - Payment succeeds (default)"
            echo "  add_balance          - Funds added to balance"
            echo "  decline              - Card is declined"
            echo "  insufficient_funds   - Insufficient funds"
            echo "  lost_card            - Lost card"
            echo "  stolen_card          - Stolen card"
            echo "  requires_authentication, 3ds - Requires 3D Secure authentication"
            echo "  requires_payment_method - Requires payment method"
            echo "  processing_error     - Processing error"
            exit 1
            ;;
    esac
}

# Parse arguments
if [ $# -lt 1 ]; then
    echo "Error: Missing required arguments"
    echo "Usage: $0 <payment_intent_id> [client_secret] [card_type]"
    echo ""
    echo "Arguments:"
    echo "  payment_intent_id - The Stripe PaymentIntent ID (e.g., pi_1234567890)"
    echo "  client_secret     - Optional: The client secret for the PaymentIntent"
    echo "  card_type         - Optional: Type of test card to use (default: success)"
    echo ""
    echo "Available card types:"
    echo "  success              - Payment succeeds (default)"
    echo "  add_balance          - Funds added to balance"
    echo "  decline              - Card is declined"
    echo "  insufficient_funds   - Insufficient funds"
    echo "  lost_card            - Lost card"
    echo "  stolen_card          - Stolen card"
    echo "  requires_authentication, 3ds - Requires 3D Secure authentication"
    echo "  requires_payment_method - Requires payment method"
    echo "  processing_error     - Processing error"
    echo ""
    echo "Environment variables:"
    echo "  STRIPE_API_KEY    - Stripe API key (required, e.g., sk_test_...)"
    echo ""
    echo "Examples:"
    echo "  ./confirm-payment.sh pi_1234567890"
    echo "  ./confirm-payment.sh pi_1234567890 pi_1234567890_secret_abc123"
    echo "  ./confirm-payment.sh pi_1234567890 \"\" decline"
    echo "  ./confirm-payment.sh pi_1234567890 pi_1234567890_secret_abc123 requires_authentication"
    exit 1
fi

PAYMENT_INTENT_ID="$1"
CLIENT_SECRET="${2:-}"
CARD_TYPE="${3:-success}"

# Validate and get card details
CARD_INFO=$(get_test_card "$CARD_TYPE")
IFS='|' read -r TEST_CARD_NUMBER TEST_CARD_EXP_MONTH TEST_CARD_EXP_YEAR TEST_CARD_CVC CARD_DESCRIPTION <<< "$CARD_INFO"

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
echo "Card Type: $CARD_TYPE"
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
echo "Card: $TEST_CARD_NUMBER ($CARD_DESCRIPTION)"
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

PAYMENT_METHOD_ID=$(echo "$PAYMENT_METHOD_RESPONSE" | jq -r '.id // empty')

if [ -z "$PAYMENT_METHOD_ID" ]; then
    echo "✗ Failed to create payment method"
    echo "Payment method ID: $PAYMENT_METHOD_ID"
    echo "Raw response: $PAYMENT_METHOD_RESPONSE"
    ERROR_MSG=$(echo "$PAYMENT_METHOD_RESPONSE" | jq -r '.error.message // empty' 2>/dev/null || echo "")
    if [ -n "$ERROR_MSG" ]; then
        echo "Error: $ERROR_MSG"
    else
        echo "$PAYMENT_METHOD_RESPONSE"
    fi
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

# Add return_url to handle redirect-based payment methods (e.g., Link)
# Using a dummy URL since we're using a card payment method that won't redirect
CONFIRM_DATA="$CONFIRM_DATA&return_url=https://example.com/return"

CONFIRM_RESPONSE=$(curl -s -X POST "https://api.stripe.com/v1/payment_intents/$PAYMENT_INTENT_ID/confirm" \
    -u "$STRIPE_API_KEY:" \
    -d "$CONFIRM_DATA")

# Check if confirmation was successful
STATUS_CHECK=$(echo "$CONFIRM_RESPONSE" | jq -r '.status // empty' 2>/dev/null || echo "")
if [ "$STATUS_CHECK" = "succeeded" ] || [ "$STATUS_CHECK" = "requires_capture" ]; then
    echo ""
    echo "=========================================="
    echo "✓ Payment confirmed successfully!"
    echo "=========================================="
    echo ""
    
    # Extract status from response
    STATUS=$(echo "$CONFIRM_RESPONSE" | jq -r '.status // empty')
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
    ERROR_MSG=$(echo "$CONFIRM_RESPONSE" | jq -r '.error.message // empty' 2>/dev/null || echo "")
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

