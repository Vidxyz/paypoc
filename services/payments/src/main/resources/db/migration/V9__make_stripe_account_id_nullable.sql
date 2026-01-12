-- Make stripe_account_id nullable in seller_stripe_accounts table
-- This allows sellers to be created without a Stripe account initially
-- They can add their Stripe account later via the seller console
ALTER TABLE seller_stripe_accounts 
    ALTER COLUMN stripe_account_id DROP NOT NULL;

COMMENT ON COLUMN seller_stripe_accounts.stripe_account_id IS 'Stripe connected account ID (e.g., acct_1234567890). NULL until seller provides their Stripe account ID.';

