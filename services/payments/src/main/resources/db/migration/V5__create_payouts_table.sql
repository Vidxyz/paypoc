-- Create payouts table
-- This table stores payout workflow state, similar to payments and refunds tables
CREATE TABLE payouts (
    id UUID PRIMARY KEY,
    seller_id TEXT NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency TEXT NOT NULL,
    state TEXT NOT NULL,
    stripe_transfer_id TEXT UNIQUE,
    ledger_transaction_id UUID,  -- NULL until payout confirmed (ledger write happens after Stripe confirms)
    idempotency_key TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    failure_reason TEXT,
    
    CONSTRAINT payout_state_valid CHECK (state IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT payout_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT payout_positive_amount CHECK (amount_cents > 0)
);

-- Create indexes
CREATE INDEX idx_payouts_seller_id ON payouts(seller_id);
CREATE INDEX idx_payouts_state ON payouts(state);
CREATE INDEX idx_payouts_stripe_transfer_id ON payouts(stripe_transfer_id);
CREATE INDEX idx_payouts_idempotency_key ON payouts(idempotency_key);
CREATE INDEX idx_payouts_ledger_transaction_id ON payouts(ledger_transaction_id);
CREATE INDEX idx_payouts_created_at ON payouts(created_at);

-- Add comments
COMMENT ON TABLE payouts IS 'Payout workflow state table - stores orchestration state only, not financial truth';
COMMENT ON COLUMN payouts.amount_cents IS 'Amount to transfer to seller (metadata only - actual money is in ledger)';
COMMENT ON COLUMN payouts.stripe_transfer_id IS 'Stripe Transfer ID';
COMMENT ON COLUMN payouts.ledger_transaction_id IS 'Reference to ledger transaction - NULL until payout confirmed, ledger is source of truth';
COMMENT ON COLUMN payouts.state IS 'Workflow state - PENDING, PROCESSING, COMPLETED, FAILED';
COMMENT ON COLUMN payouts.completed_at IS 'Timestamp when payout completed (state = COMPLETED)';
COMMENT ON COLUMN payouts.failure_reason IS 'Reason for payout failure (if state = FAILED)';

