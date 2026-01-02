-- Create ledger_accounts table
CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    type TEXT NOT NULL,
    currency TEXT NOT NULL,
    status TEXT NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT account_type_valid CHECK (type IN ('CUSTOMER', 'MERCHANT', 'PSP_CLEARING', 'FEE', 'REFUND')),
    CONSTRAINT account_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED', 'CLOSED'))
);

-- Create indexes
CREATE INDEX idx_ledger_accounts_currency ON ledger_accounts(currency);
CREATE INDEX idx_ledger_accounts_type ON ledger_accounts(type);
CREATE INDEX idx_ledger_accounts_status ON ledger_accounts(status);

-- Add comments
COMMENT ON TABLE ledger_accounts IS 'Ledger accounts table - stores account identifiers, type, status, and metadata';
COMMENT ON COLUMN ledger_accounts.type IS 'Account type: CUSTOMER, MERCHANT, PSP_CLEARING, FEE, REFUND';
COMMENT ON COLUMN ledger_accounts.status IS 'Account status: ACTIVE, INACTIVE, SUSPENDED, CLOSED';
COMMENT ON COLUMN ledger_accounts.metadata IS 'Additional account metadata stored as JSON';

