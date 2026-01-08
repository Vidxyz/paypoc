#!/bin/bash
# create-intent.sh

# 1. Set your Secret Key

# 2. Create the PaymentIntent
# We set the amount to 100000 ($1,000.00 CAD)
RESPONSE=$(curl -s https://api.stripe.com/v1/payment_intents \
  -u "$STRIPE_API_KEY:" \
  -d "amount=100000" \
  -d "currency=cad" \
  -d "payment_method_types[]=card" \
  -d "description=Funding platform balance for testing with special card")

# 3. Extract the ID and Client Secret
PI_ID=$(echo "$RESPONSE" | grep -o '"id": "[^"]*"' | head -1 | cut -d'"' -f4)
CLIENT_SECRET=$(echo "$RESPONSE" | grep -o '"client_secret": "[^"]*"' | cut -d'"' -f4)

echo "PaymentIntent Created!"
echo "ID: $PI_ID"
echo "Client Secret: $CLIENT_SECRET"