-- Create payments table
-- This table stores workflow state ONLY, not financial truth
-- If this database is deleted, money is still correct in the ledger
CREATE TABLE payments (
    id UUID PRIMARY KEY,
    amount_cents BIGINT NOT NULL,
    currency TEXT NOT NULL,
    state TEXT NOT NULL,
    ledger_transaction_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    
    CONSTRAINT state_valid CHECK (state IN ('CREATED', 'CONFIRMING', 'AUTHORIZED', 'CAPTURED', 'FAILED'))
);

-- Create indexes
CREATE INDEX idx_payments_state ON payments(state);
CREATE INDEX idx_payments_ledger_transaction_id ON payments(ledger_transaction_id);
CREATE INDEX idx_payments_idempotency_key ON payments(idempotency_key);

-- Add comments
COMMENT ON TABLE payments IS 'Payment workflow state table - stores orchestration state only, not financial truth';
COMMENT ON COLUMN payments.amount_cents IS 'Metadata only - actual money is in ledger';
COMMENT ON COLUMN payments.ledger_transaction_id IS 'Reference to ledger transaction - ledger is source of truth';
COMMENT ON COLUMN payments.state IS 'Workflow state - CREATED, CONFIRMING, AUTHORIZED, CAPTURED, FAILED';

