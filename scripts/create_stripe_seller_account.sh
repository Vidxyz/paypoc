# Create a Stripe seller account
# Usage: ./create_stripe_seller_account.sh
# This script creates a Stripe seller account for the user "tester@buyit.com"
# It uses the Stripe API to create the account
# It uses the Stripe API key from the environment variable STRIPE_SECRET_KEY
stripe accounts create \
  -d type=custom \
  -d country=US \
  -d business_type=individual \
  -d email="tester@buyit.com" \
  -d "business_profile[name]=Seller Account" \
  -d "business_profile[url]=https://buyit.com" \
  -d "business_profile[mcc]=5732" \
  -d "individual[first_name]=Test" \
  -d "individual[last_name]=User" \
  -d "individual[email]=tester@buyit.com" \
  -d "individual[address][line1]=123 King St W" \
  -d "individual[address][city]=Toronto" \
  -d "individual[address][state]=OH" \
  -d "individual[address][postal_code]=12345" \
  -d "individual[dob][day]=01" \
  -d "individual[dob][month]=01" \
  -d "individual[dob][year]=1990" \
  -d "individual[phone]=4155552671" \
  -d "individual[id_number]=000000000" \
  -d "external_account=btok_us_verified" \
  -d "capabilities[card_payments][requested]=true" \
  -d "capabilities[transfers][requested]=true" \
  -d "tos_acceptance[date]=$(date +%s)" \
  -d "tos_acceptance[ip]=127.0.0.1" \
  -d "business_profile[product_description]=Custom software development services for BuyIt platform." \
  -d "individual[relationship][title]=CEO" \
  -d "individual[verification][document][front]=file_identity_document_success" \
  -d "individual[verification][additional_document][front]=file_identity_document_success" \
  --api-key "$STRIPE_SECRET_KEY"
