-- Create ledger_accounts table
CREATE TABLE ledger_accounts (
    account_id UUID PRIMARY KEY,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT currency_format CHECK (currency ~ '^[A-Z]{3}$')
);

-- Create index for currency lookups
CREATE INDEX idx_ledger_accounts_currency ON ledger_accounts(currency);

-- Add comment
COMMENT ON TABLE ledger_accounts IS 'Ledger accounts table - stores account identifiers and currency';

