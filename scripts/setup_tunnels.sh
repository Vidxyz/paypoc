#!/bin/bash

# Configuration
STRIPE_TARGET="payments.local/webhooks/stripe"

echo "🚀 Starting Stripe Listen and Minikube Tunnel..."

# 1. Start Stripe Listen in the background
# We redirect output to a log file or /dev/null to keep the terminal clean
stripe listen --forward-to "$STRIPE_TARGET" > .stripe_logs 2>&1 &
STRIPE_PID=$!
echo "✅ Stripe CLI listening (PID: $STRIPE_PID)"

# 2. Start Minikube Tunnel in the background
# This usually requires sudo, so it may prompt for a password immediately
echo "🔐 Requesting sudo for Minikube Tunnel..."
sudo -v # Pre-validate sudo so it doesn't hang in the background
nohup minikube tunnel > /dev/null 2>&1 &
TUNNEL_PID=$!
echo "✅ Minikube Tunnel active (PID: $TUNNEL_PID)"

# 3. Define the cleanup function
cleanup() {
    echo -e "\n🛑 Stopping background processes..."
    kill $STRIPE_PID
    # Minikube tunnel creates subprocesses; we kill the main PID
    sudo kill $TUNNEL_PID
    echo "Done. Goodbye!"
    exit
}

# 4. Capture Ctrl+C (SIGINT) and call cleanup
trap cleanup SIGINT

echo "-------------------------------------------------------"
echo "Press [Ctrl+C] to stop both tunnels."
echo "-------------------------------------------------------"

# Keep the script running in the foreground
while true; do
    sleep 1
done