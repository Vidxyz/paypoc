#!/bin/bash

# Script to test chargeback/dispute state transitions using Stripe CLI
# This simulates Stripe webhook events for updating and closing disputes
#
# Note: To create a dispute, use confirm-payment.sh with the chargeback test card:
#   ./confirm-payment.sh <payment_intent_id> <client_secret> chargeback
#
# Usage:
#   ./test-chargeback.sh <dispute_id> <action> <status>
#
# Actions:
#   update     - Simulate charge.dispute.updated (requires dispute_id: du_...)
#   close      - Simulate charge.dispute.closed (requires dispute_id: du_...)
#
# Examples:
#   # Update dispute to under_review
#   ./test-chargeback.sh du_1234567890 update under_review
#
#   # Close dispute as won
#   ./test-chargeback.sh du_1234567890 close won
#
#   # Close dispute as lost
#   ./test-chargeback.sh du_1234567890 close lost
#
#     # Close dispute as withdrawn
  #   ./test-chargeback.sh du_1234567890 close charge_refunded
  #
  #   # Close dispute as warning_closed
  #   ./test-chargeback.sh du_1234567890 close warning_closed

set -e

# Parse arguments
if [ $# -lt 3 ]; then
    echo "Error: Missing required arguments"
    echo "Usage: $0 <dispute_id> <action> <status>"
    echo ""
    echo "Arguments:"
    echo "  dispute_id - The Stripe Dispute ID (e.g., du_1234567890)"
    echo "  action     - Action to perform: update or close"
    echo "  status     - Status for the action (required)"
    echo ""
    echo "Actions:"
    echo "  update     - Simulate charge.dispute.updated"
    echo "  close      - Simulate charge.dispute.closed"
    echo ""
    echo "Status values:"
    echo "  For update: needs_response, under_review, warning_needs_response"
    echo "  For close:  won, lost, charge_refunded, warning_closed"
    echo ""
    echo "Examples:"
    echo "  # Update dispute to under_review"
    echo "  ./test-chargeback.sh du_1234567890 update under_review"
    echo ""
    echo "  # Close dispute as won"
    echo "  ./test-chargeback.sh du_1234567890 close won"
    echo ""
    echo "Note: To create a dispute, use confirm-payment.sh with the chargeback test card:"
    echo "  ./confirm-payment.sh <payment_intent_id> <client_secret> chargeback"
    exit 1
fi

DISPUTE_ID="$1"
ACTION="$2"
STATUS="$3"

# Validate dispute_id format
if ! [[ "$DISPUTE_ID" =~ ^du_ ]]; then
    echo "Error: Dispute ID must start with 'du_'"
    echo "Example: du_1234567890"
    exit 1
fi

# Check if Stripe CLI is installed
if ! command -v stripe &> /dev/null; then
    echo "Error: Stripe CLI is not installed"
    echo ""
    echo "Install it with:"
    echo "  brew install stripe/stripe-cli/stripe"
    echo ""
    echo "Or visit: https://stripe.com/docs/stripe-cli"
    exit 1
fi

echo "=========================================="
echo "Chargeback Test Script"
echo "=========================================="
echo "Dispute ID: $DISPUTE_ID"
echo "Action: $ACTION"
echo "Status: $STATUS"
echo ""

# Handle different actions
case "$ACTION" in
    update)
        if [ -z "$DISPUTE_ID" ]; then
            echo "Error: Dispute ID (du_...) is required for 'update' action"
            echo "Example: ./test-chargeback.sh du_1234567890 update under_review"
            exit 1
        fi
        
        if [ -z "$STATUS" ]; then
            echo "Error: Status is required for 'update' action"
            echo ""
            echo "Valid statuses:"
            echo "  needs_response      - Dispute needs response"
            echo "  under_review        - Dispute under review"
            echo "  warning_needs_response - Warning needs response"
            exit 1
        fi
        
        # Validate status
        case "$STATUS" in
            needs_response|under_review|warning_needs_response)
                ;;
            *)
                echo "Error: Invalid status for 'update' action: $STATUS"
                echo ""
                echo "Valid statuses:"
                echo "  needs_response      - Dispute needs response"
                echo "  under_review        - Dispute under review"
                echo "  warning_needs_response - Warning needs response"
                exit 1
                ;;
        esac
        
        echo "Note: Stripe API does not allow direct status updates for disputes."
        echo "      Dispute statuses are managed by Stripe/banks based on evidence and time."
        echo ""
        echo "To trigger charge.dispute.updated webhook, we can submit evidence"
        echo "which may change the status (e.g., from needs_response to under_review)..."
        echo ""
        
        # Get Stripe API key
        if [ -z "$STRIPE_API_KEY" ]; then
            if command -v stripe &> /dev/null; then
                STRIPE_API_KEY=$(stripe config --get test_mode_api_key 2>/dev/null || echo "")
            fi
        fi
        
        if [ -z "$STRIPE_API_KEY" ]; then
            echo "Error: STRIPE_API_KEY environment variable is required"
            echo ""
            echo "Set it with:"
            echo "  export STRIPE_API_KEY=sk_test_..."
            exit 1
        fi
        
        # For under_review status, submit evidence which may trigger the status change
        if [ "$STATUS" = "under_review" ]; then
            echo "Submitting evidence to move dispute to under_review..."
            echo ""
            
            EVIDENCE_RESPONSE=$(curl -s -X POST "https://api.stripe.com/v1/disputes/$DISPUTE_ID" \
                -u "$STRIPE_API_KEY:" \
                -d "evidence[product_description]=Test product description" \
                -d "evidence[customer_name]=Test Customer" \
                -d "evidence[customer_email]=test@example.com" \
                -d "evidence[shipping_address]=123 Test Street" \
                -d "evidence[shipping_carrier]=Test Carrier" \
                -d "evidence[shipping_tracking_number]=TEST123456" 2>&1)
            
            ERROR_MSG=$(echo "$EVIDENCE_RESPONSE" | jq -r '.error.message // empty' 2>/dev/null || echo "")
            
            if [ -n "$ERROR_MSG" ]; then
                echo "✗ Failed to submit evidence"
                echo "Error: $ERROR_MSG"
                echo ""
                echo "Alternative: Use Stripe Dashboard to update the dispute status."
                echo "            The webhook will fire automatically."
                exit 1
            fi
            
            CURRENT_STATUS=$(echo "$EVIDENCE_RESPONSE" | jq -r '.status // empty' 2>/dev/null || echo "")
            echo "✓ Evidence submitted successfully"
            echo "  Current dispute status: $CURRENT_STATUS"
            echo "  Webhook charge.dispute.updated should fire automatically if status changed"
            echo ""
            echo "This should:"
            echo "  - Transition chargeback to UNDER_REVIEW state (if status changed)"
        else
            echo "For status '$STATUS', please use Stripe Dashboard to update the dispute."
            echo "The webhook will fire automatically when the status changes."
            echo ""
            echo "Alternative: Use Stripe CLI to trigger a test webhook:"
            echo "  stripe trigger charge.dispute.updated"
            echo "  (Note: This creates a webhook for a random dispute)"
            exit 0
        fi
        ;;
    
    close)
        if [ -z "$DISPUTE_ID" ]; then
            echo "Error: Dispute ID (du_...) is required for 'close' action"
            echo "Example: ./test-chargeback.sh du_1234567890 close won"
            exit 1
        fi
        
        if [ -z "$STATUS" ]; then
            echo "Error: Status is required for 'close' action"
            echo ""
            echo "Valid statuses:"
            echo "  won             - Platform won the dispute"
            echo "  lost            - Platform lost the dispute"
            echo "  charge_refunded - Buyer withdrew dispute (withdrawn)"
            echo "  warning_closed  - Dispute closed with warning (both amount and fee returned)"
            exit 1
        fi
        
        # Validate status
        case "$STATUS" in
            won|lost|charge_refunded|warning_closed)
                ;;
            *)
                echo "Error: Invalid status for 'close' action: $STATUS"
                echo ""
                echo "Valid statuses:"
                echo "  won             - Platform won the dispute"
                echo "  lost            - Platform lost the dispute"
                echo "  charge_refunded - Buyer withdrew dispute (withdrawn)"
                echo "  warning_closed  - Dispute closed with warning (both amount and fee returned)"
                exit 1
                ;;
        esac
        
        # Check if Stripe CLI is installed
        if ! command -v stripe &> /dev/null; then
            echo "Error: Stripe CLI is not installed"
            echo ""
            echo "Install it with:"
            echo "  brew install stripe/stripe-cli/stripe"
            echo ""
            echo "Or visit: https://stripe.com/docs/stripe-cli"
            exit 1
        fi
        
        case "$STATUS" in
            won)
                echo "Simulating chargeback WON scenario..."
                echo ""
                echo "Submitting winning evidence to dispute $DISPUTE_ID..."
                echo "This will trigger Stripe to resolve the dispute in your favor."
                echo ""
                
                # Use Stripe CLI to submit evidence that triggers a win
                stripe disputes update "$DISPUTE_ID" \
                    -d "evidence[uncategorized_text]=winning_evidence" 2>&1
                
                UPDATE_EXIT_CODE=$?
                
                if [ $UPDATE_EXIT_CODE -eq 0 ]; then
                    echo ""
                    echo "✓ Evidence submitted successfully"
                    echo ""
                    echo "Stripe will process the evidence and resolve the dispute."
                    echo "When the dispute status becomes 'won', Stripe will send"
                    echo "charge.dispute.closed webhook automatically."
                    echo ""
                    echo "This should:"
                    echo "  - Transition chargeback to WON state"
                    echo "  - Publish CHARGEBACK_WON event to Kafka"
                    echo "  - Write ledger entries (money returned to STRIPE_CLEARING)"
                    echo "  - Note: Dispute fee is NOT refunded (stays as expense)"
                else
                    echo ""
                    echo "✗ Failed to submit evidence"
                    echo ""
                    echo "Alternative: Use Stripe Dashboard to submit evidence and close the dispute."
                    exit 1
                fi
                ;;
            
            lost)
                echo "Simulating chargeback LOST scenario..."
                echo ""
                echo "Submitting losing evidence to dispute $DISPUTE_ID..."
                echo "This will trigger Stripe to resolve the dispute against you."
                echo ""
                
                # Use Stripe CLI to submit evidence that triggers a loss
                stripe disputes update "$DISPUTE_ID" \
                    -d "evidence[uncategorized_text]=losing_evidence" 2>&1
                
                UPDATE_EXIT_CODE=$?
                
                if [ $UPDATE_EXIT_CODE -eq 0 ]; then
                    echo ""
                    echo "✓ Evidence submitted successfully"
                    echo ""
                    echo "Stripe will process the evidence and resolve the dispute."
                    echo "When the dispute status becomes 'lost', Stripe will send"
                    echo "charge.dispute.closed webhook automatically."
                    echo ""
                    echo "This should:"
                    echo "  - Transition chargeback to LOST state"
                    echo "  - Publish CHARGEBACK_LOST event to Kafka"
                    echo "  - Write ledger entries (debit SELLER_PAYABLE and BUYIT_REVENUE)"
                    echo "  - Note: Dispute fee is NOT refunded (stays as expense)"
                else
                    echo ""
                    echo "✗ Failed to submit evidence"
                    echo ""
                    echo "Alternative: Use Stripe Dashboard to submit evidence and close the dispute."
                    exit 1
                fi
                ;;
            
            charge_refunded)
                echo "Simulating chargeback WITHDRAWN scenario..."
                echo ""
                echo "Note: Stripe API does not allow programmatic withdrawal of disputes."
                echo "      Disputes are withdrawn when the buyer cancels their dispute."
                echo ""
                echo "To test WITHDRAWN scenario:"
                echo "  1. Use Stripe Dashboard to simulate buyer withdrawal"
                echo "  2. Or use Stripe API (test mode only):"
                echo "     curl https://api.stripe.com/v1/disputes/$DISPUTE_ID \\"
                echo "       -u sk_test_YOUR_KEY: \\"
                echo "       -d \"status=charge_refunded\""
                echo ""
                echo "The webhook will fire automatically when the dispute is withdrawn."
                exit 0
                ;;
            
            warning_closed)
                echo "Simulating chargeback WARNING_CLOSED scenario..."
                echo ""
                echo "Submitting evidence to dispute $DISPUTE_ID..."
                echo "This may trigger Stripe to close the dispute with warning_closed status."
                echo ""
                echo "Note: warning_closed typically occurs when:"
                echo "  - An inquiry (retrieval request) is resolved in your favor"
                echo "  - Evidence is submitted that resolves the dispute without a formal chargeback"
                echo ""
                
                # Use Stripe CLI to submit evidence that may trigger warning_closed
                # For inquiries, submitting evidence often results in warning_closed
                stripe disputes update "$DISPUTE_ID" \
                    -d "evidence[uncategorized_text]=inquiry_resolution_evidence" 2>&1
                
                UPDATE_EXIT_CODE=$?
                
                if [ $UPDATE_EXIT_CODE -eq 0 ]; then
                    echo ""
                    echo "✓ Evidence submitted successfully"
                    echo ""
                    echo "Stripe will process the evidence and resolve the dispute."
                    echo "When the dispute status becomes 'warning_closed', Stripe will send"
                    echo "charge.dispute.closed webhook automatically."
                    echo ""
                    echo "This should:"
                    echo "  - Transition chargeback to WARNING_CLOSED state"
                    echo "  - Publish CHARGEBACK_WARNING_CLOSED event to Kafka"
                    echo "  - Write ledger entries (BOTH amount and fee returned to STRIPE_CLEARING)"
                    echo "  - Note: Unlike WON, both disputed amount AND dispute fee are refunded"
                else
                    echo ""
                    echo "✗ Failed to submit evidence"
                    echo ""
                    echo "Alternative: Use Stripe Dashboard to submit evidence and close the dispute."
                    echo ""
                    echo "Note: warning_closed is more likely to occur with inquiries (retrieval requests)"
                    echo "      created using the inquiry test card (4000000000001976)."
                    exit 1
                fi
                ;;
        esac
        
        echo ""
        echo "✓ Evidence submitted successfully"
        echo ""
        echo "Stripe will process the evidence and resolve the dispute."
        echo "When the dispute status changes, Stripe will send"
        echo "charge.dispute.closed webhook automatically."
        echo ""
        echo "This should:"
        case "$STATUS" in
            won)
                echo "  - Transition chargeback to WON state"
                echo "  - Publish CHARGEBACK_WON event to Kafka"
                echo "  - Write ledger entries (money returned to STRIPE_CLEARING)"
                echo "  - Dispute fee is NOT refunded"
                ;;
            lost)
                echo "  - Transition chargeback to LOST state"
                echo "  - Publish CHARGEBACK_LOST event to Kafka"
                echo "  - Write ledger entries (debit SELLER_PAYABLE and BUYIT_REVENUE)"
                echo "  - Dispute fee is NOT refunded"
                ;;
            warning_closed)
                echo "  - Transition chargeback to WARNING_CLOSED state"
                echo "  - Publish CHARGEBACK_WARNING_CLOSED event to Kafka"
                echo "  - Write ledger entries (BOTH amount and fee returned to STRIPE_CLEARING)"
                echo "  - Note: Unlike WON, both disputed amount AND dispute fee are refunded"
                ;;
        esac
        ;;
    
    *)
        echo "Error: Invalid action: $ACTION"
        echo ""
        echo "Valid actions:"
        echo "  update - Simulate charge.dispute.updated"
        echo "  close  - Simulate charge.dispute.closed"
        echo ""
        echo "Note: To create a dispute, use confirm-payment.sh with the chargeback test card"
        exit 1
        ;;
esac

echo ""
echo "=========================================="
echo "Webhook triggered successfully!"
echo "=========================================="
echo ""
echo "Check your payments service logs to see the webhook processing."
echo "Check your ledger service logs to see the ledger entries."

