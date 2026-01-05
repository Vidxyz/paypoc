-- Create ledger_transactions table for transaction metadata
CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    reference_id TEXT NOT NULL UNIQUE,  -- External reference (e.g., Stripe paymentIntent ID)
    idempotency_key TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Create indexes
CREATE INDEX idx_ledger_transactions_reference_id ON ledger_transactions(reference_id);
CREATE INDEX idx_ledger_transactions_idempotency_key ON ledger_transactions(idempotency_key);
CREATE INDEX idx_ledger_transactions_created_at ON ledger_transactions(created_at);

-- Add comments
COMMENT ON TABLE ledger_transactions IS 'Ledger transactions table - metadata for financial transactions (idempotency, auditing, reconciliation)';
COMMENT ON COLUMN ledger_transactions.reference_id IS 'External reference ID (e.g., Stripe paymentIntent ID, payout ID, refund ID)';
COMMENT ON COLUMN ledger_transactions.idempotency_key IS 'Unique key to prevent duplicate processing of the same transaction';
COMMENT ON COLUMN ledger_transactions.description IS 'Human-readable description of the transaction';

