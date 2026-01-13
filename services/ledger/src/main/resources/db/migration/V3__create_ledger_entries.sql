-- Create ledger_entries table for double-entry bookkeeping
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    account_id UUID NOT NULL,
    direction TEXT NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_transaction
        FOREIGN KEY (transaction_id)
        REFERENCES ledger_transactions(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_account
        FOREIGN KEY (account_id)
        REFERENCES ledger_accounts(id)
        ON DELETE RESTRICT,

    CONSTRAINT direction_valid
        CHECK (direction IN ('DEBIT', 'CREDIT')),

    CONSTRAINT positive_amount
        CHECK (amount_cents > 0),

    CONSTRAINT currency_format
        CHECK (currency ~ '^[A-Z]{3}$')
);

-- Create indexes for balance queries and aggregations
CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_entries_account_id ON ledger_entries(account_id);
CREATE INDEX idx_ledger_entries_account_created ON ledger_entries(account_id, created_at);
CREATE INDEX idx_ledger_entries_direction ON ledger_entries(direction);

-- Add comments
COMMENT ON TABLE ledger_entries IS 'Ledger entries table - double-entry bookkeeping entries (DEBIT/CREDIT)';
COMMENT ON COLUMN ledger_entries.transaction_id IS 'Reference to the parent transaction';
COMMENT ON COLUMN ledger_entries.account_id IS 'Reference to the account this entry affects';
COMMENT ON COLUMN ledger_entries.direction IS 'DEBIT or CREDIT';
COMMENT ON COLUMN ledger_entries.amount_cents IS 'Amount in cents (always positive, direction indicates DEBIT/CREDIT)';
COMMENT ON COLUMN ledger_entries.currency IS 'Currency code (ISO 4217, e.g., CAD)';

