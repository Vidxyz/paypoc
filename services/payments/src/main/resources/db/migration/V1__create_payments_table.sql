-- Create payments table
-- This table stores workflow state ONLY, not financial truth
-- If this database is deleted, money is still correct in the ledger
CREATE TABLE payments (
    id UUID PRIMARY KEY,
    buyer_id TEXT NOT NULL,
    seller_id TEXT NOT NULL,
    gross_amount_cents BIGINT NOT NULL,
    platform_fee_cents BIGINT NOT NULL,
    net_seller_amount_cents BIGINT NOT NULL,
    currency TEXT NOT NULL,
    state TEXT NOT NULL,
    stripe_payment_intent_id TEXT,
    ledger_transaction_id UUID,  -- NULL until capture (ledger write happens after Stripe confirms)
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    CONSTRAINT state_valid CHECK (state IN ('CREATED', 'CONFIRMING', 'AUTHORIZED', 'CAPTURED', 'FAILED')),
    CONSTRAINT currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT positive_gross_amount CHECK (gross_amount_cents > 0),
    CONSTRAINT non_negative_platform_fee CHECK (platform_fee_cents >= 0),
    CONSTRAINT positive_net_seller_amount CHECK (net_seller_amount_cents > 0),
    CONSTRAINT fee_calculation CHECK (gross_amount_cents = platform_fee_cents + net_seller_amount_cents)
);

-- Create indexes
CREATE INDEX idx_payments_state ON payments(state);
CREATE INDEX idx_payments_buyer_id ON payments(buyer_id);
CREATE INDEX idx_payments_seller_id ON payments(seller_id);
CREATE INDEX idx_payments_stripe_payment_intent_id ON payments(stripe_payment_intent_id);
CREATE INDEX idx_payments_ledger_transaction_id ON payments(ledger_transaction_id);
CREATE INDEX idx_payments_idempotency_key ON payments(idempotency_key);

-- Add comments
COMMENT ON TABLE payments IS 'Payment workflow state table - stores orchestration state only, not financial truth';
COMMENT ON COLUMN payments.gross_amount_cents IS 'Total payment amount (metadata only - actual money is in ledger)';
COMMENT ON COLUMN payments.platform_fee_cents IS 'BuyIt platform commission (10% of gross)';
COMMENT ON COLUMN payments.net_seller_amount_cents IS 'Amount seller will receive (90% of gross)';
COMMENT ON COLUMN payments.stripe_payment_intent_id IS 'Stripe PaymentIntent ID';
COMMENT ON COLUMN payments.ledger_transaction_id IS 'Reference to ledger transaction - NULL until capture, ledger is source of truth';
COMMENT ON COLUMN payments.state IS 'Workflow state - CREATED, CONFIRMING, AUTHORIZED, CAPTURED, FAILED';
