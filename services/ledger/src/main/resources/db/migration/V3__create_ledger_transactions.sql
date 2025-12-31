-- Create ledger_transactions table
CREATE TABLE ledger_transactions (
    transaction_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    idempotency_key TEXT NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT fk_account
        FOREIGN KEY (account_id)
        REFERENCES ledger_accounts(account_id)
        ON DELETE RESTRICT,

    CONSTRAINT non_zero_amount
        CHECK (amount_cents <> 0),

    CONSTRAINT currency_format
        CHECK (currency ~ '^[A-Z]{3}$')
);

-- Create unique index on idempotency_key to enforce idempotency
CREATE UNIQUE INDEX idx_ledger_transactions_idempotency_key ON ledger_transactions(idempotency_key);

-- Create indexes for balance queries
CREATE INDEX idx_ledger_transactions_account_id ON ledger_transactions(account_id);
CREATE INDEX idx_ledger_transactions_account_created ON ledger_transactions(account_id, created_at);

-- Add comment
COMMENT ON TABLE ledger_transactions IS 'Ledger transactions table - append-only record of all financial transactions';
COMMENT ON COLUMN ledger_transactions.amount_cents IS 'Amount in cents. Positive for credits, negative for debits.';
COMMENT ON COLUMN ledger_transactions.idempotency_key IS 'Unique key to prevent duplicate processing of the same transaction';

