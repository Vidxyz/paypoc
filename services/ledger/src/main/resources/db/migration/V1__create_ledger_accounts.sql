-- Create ledger_accounts table with new schema
CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    account_type TEXT NOT NULL,
    reference_id TEXT,
    currency TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT account_type_valid CHECK (account_type IN (
        'STRIPE_CLEARING',
        'SELLER_PAYABLE',
        'BUYIT_REVENUE',
        'FEES_EXPENSE',
        'REFUNDS_CLEARING',
        'CHARGEBACK_CLEARING',
        'BUYER_EXTERNAL'  -- Logical account, not real balance
    )),
    
    -- Ensure uniqueness: same account_type + reference_id combination is unique
    UNIQUE (account_type, reference_id)
);

-- Create indexes
CREATE INDEX idx_ledger_accounts_account_type ON ledger_accounts(account_type);
CREATE INDEX idx_ledger_accounts_reference_id ON ledger_accounts(reference_id);
CREATE INDEX idx_ledger_accounts_currency ON ledger_accounts(currency);

-- Add comments
COMMENT ON TABLE ledger_accounts IS 'Ledger accounts table - stores internal accounts for double-entry bookkeeping';
COMMENT ON COLUMN ledger_accounts.account_type IS 'Account type: STRIPE_CLEARING, SELLER_PAYABLE, BUYIT_REVENUE, etc.';
COMMENT ON COLUMN ledger_accounts.reference_id IS 'Optional reference ID (e.g., seller_id for SELLER_PAYABLE accounts)';
COMMENT ON COLUMN ledger_accounts.currency IS 'Currency code (ISO 4217, e.g., USD)';
