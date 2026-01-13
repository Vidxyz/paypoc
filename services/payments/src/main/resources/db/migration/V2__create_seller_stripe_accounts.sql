-- Create seller_stripe_accounts table
-- Maps seller_id to their Stripe connected account ID
-- Supports multiple currencies per seller (one Stripe account per currency)
CREATE TABLE seller_stripe_accounts (
    seller_id TEXT NOT NULL,
    stripe_account_id TEXT NOT NULL,
    currency TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    PRIMARY KEY (seller_id, currency),
    CONSTRAINT currency_format CHECK (currency ~ '^[A-Z]{3}$')
);

-- Create indexes
CREATE INDEX idx_seller_stripe_accounts_seller_id ON seller_stripe_accounts(seller_id);
CREATE INDEX idx_seller_stripe_accounts_currency ON seller_stripe_accounts(currency);

-- Add comments
COMMENT ON TABLE seller_stripe_accounts IS 'Maps seller_id to Stripe connected account ID, supports multiple currencies per seller';
COMMENT ON COLUMN seller_stripe_accounts.seller_id IS 'Seller identifier (business ID)';
COMMENT ON COLUMN seller_stripe_accounts.stripe_account_id IS 'Stripe connected account ID (e.g., acct_1234567890)';
COMMENT ON COLUMN seller_stripe_accounts.currency IS 'Currency code (ISO 4217, e.g., CAD)';

