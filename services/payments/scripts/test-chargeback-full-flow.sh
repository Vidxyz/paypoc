#!/bin/bash

# Script to test a complete chargeback flow from creation to closure
# This simulates the full lifecycle of a chargeback
#
# Usage:
#   ./test-chargeback-full-flow.sh <charge_id> [outcome]
#
# Outcomes:
#   won         - Platform wins the dispute (default)
#   lost        - Platform loses the dispute
#   withdrawn   - Buyer withdraws the dispute
#
# Example:
#   ./test-chargeback-full-flow.sh ch_1234567890 won
#   ./test-chargeback-full-flow.sh ch_1234567890 lost

set -e

# Parse arguments
if [ $# -lt 1 ]; then
    echo "Error: Missing required arguments"
    echo "Usage: $0 <charge_id> [outcome]"
    echo ""
    echo "Arguments:"
    echo "  charge_id - The Stripe Charge ID (e.g., ch_1234567890)"
    echo "  outcome   - Final outcome: won, lost, or withdrawn (default: won)"
    echo ""
    echo "Examples:"
    echo "  # Test chargeback flow ending in win"
    echo "  ./test-chargeback-full-flow.sh ch_1234567890 won"
    echo ""
    echo "  # Test chargeback flow ending in loss"
    echo "  ./test-chargeback-full-flow.sh ch_1234567890 lost"
    echo ""
    echo "  # Test chargeback flow ending in withdrawal"
    echo "  ./test-chargeback-full-flow.sh ch_1234567890 withdrawn"
    exit 1
fi

CHARGE_ID="$1"
OUTCOME="${2:-won}"

# Validate charge_id format
if ! [[ "$CHARGE_ID" =~ ^ch_ ]]; then
    echo "Error: Charge ID must start with 'ch_'"
    echo "Example: ch_1234567890"
    exit 1
fi

# Validate outcome
case "$OUTCOME" in
    won|lost|withdrawn)
        ;;
    *)
        echo "Error: Invalid outcome: $OUTCOME"
        echo ""
        echo "Valid outcomes:"
        echo "  won        - Platform wins the dispute"
        echo "  lost       - Platform loses the dispute"
        echo "  withdrawn  - Buyer withdraws the dispute"
        exit 1
        ;;
esac

# Get the directory of this script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_CHARGEBACK_SCRIPT="$SCRIPT_DIR/test-chargeback.sh"

# Check if test-chargeback.sh exists
if [ ! -f "$TEST_CHARGEBACK_SCRIPT" ]; then
    echo "Error: test-chargeback.sh not found at $TEST_CHARGEBACK_SCRIPT"
    exit 1
fi

echo "=========================================="
echo "Full Chargeback Flow Test"
echo "=========================================="
echo "Charge ID: $CHARGE_ID"
echo "Final Outcome: $OUTCOME"
echo ""
echo "This will simulate the complete chargeback lifecycle:"
echo "  1. Create dispute (charge.dispute.created)"
echo "  2. Update to under_review (charge.dispute.updated)"
echo "  3. Close with outcome: $OUTCOME (charge.dispute.closed)"
echo ""
read -p "Press Enter to continue or Ctrl+C to cancel..."

# Step 1: Create dispute
echo ""
echo "=========================================="
echo "Step 1: Creating dispute"
echo "=========================================="
"$TEST_CHARGEBACK_SCRIPT" "$CHARGE_ID" create
echo ""
echo "Waiting 2 seconds for webhook processing..."
sleep 2

# Step 2: Update to under_review
echo ""
echo "=========================================="
echo "Step 2: Updating to under_review"
echo "=========================================="
"$TEST_CHARGEBACK_SCRIPT" "$CHARGE_ID" update under_review
echo ""
echo "Waiting 2 seconds for webhook processing..."
sleep 2

# Step 3: Close with outcome
echo ""
echo "=========================================="
echo "Step 3: Closing dispute with outcome: $OUTCOME"
echo "=========================================="

case "$OUTCOME" in
    won)
        "$TEST_CHARGEBACK_SCRIPT" "$CHARGE_ID" close won
        ;;
    lost)
        "$TEST_CHARGEBACK_SCRIPT" "$CHARGE_ID" close lost
        ;;
    withdrawn)
        "$TEST_CHARGEBACK_SCRIPT" "$CHARGE_ID" close charge_refunded
        ;;
esac

echo ""
echo "=========================================="
echo "Full chargeback flow test completed!"
echo "=========================================="
echo ""
echo "Summary:"
echo "  - Dispute created and chargeback record created"
echo "  - Chargeback transitioned to UNDER_REVIEW"
echo "  - Chargeback closed with outcome: $OUTCOME"
echo ""
echo "Check your service logs to verify:"
echo "  - Payments service: Chargeback state transitions"
echo "  - Ledger service: Financial entries for each step"
echo ""
echo "You can verify the chargeback in your database:"
echo "  - Check the chargebacks table for the new record"
echo "  - Check the chargeback state matches the final outcome"

