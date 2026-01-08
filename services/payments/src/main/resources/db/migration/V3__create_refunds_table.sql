-- Create refunds table
-- This table stores refund workflow state, similar to payments table
CREATE TABLE refunds (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(id),
    refund_amount_cents BIGINT NOT NULL,
    platform_fee_refund_cents BIGINT NOT NULL,
    net_seller_refund_cents BIGINT NOT NULL,
    currency TEXT NOT NULL,
    state TEXT NOT NULL,
    stripe_refund_id TEXT UNIQUE,
    ledger_transaction_id UUID,  -- NULL until refund confirmed (ledger write happens after Stripe confirms)
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    CONSTRAINT refund_state_valid CHECK (state IN ('REFUNDING', 'REFUNDED', 'FAILED')),
    CONSTRAINT refund_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT refund_positive_amount CHECK (refund_amount_cents > 0),
    CONSTRAINT refund_non_negative_platform_fee CHECK (platform_fee_refund_cents >= 0),
    CONSTRAINT refund_positive_net_seller_amount CHECK (net_seller_refund_cents > 0),
    CONSTRAINT refund_fee_calculation CHECK (refund_amount_cents = platform_fee_refund_cents + net_seller_refund_cents)
);

-- Create indexes
CREATE INDEX idx_refunds_payment_id ON refunds(payment_id);
CREATE INDEX idx_refunds_state ON refunds(state);
CREATE INDEX idx_refunds_stripe_refund_id ON refunds(stripe_refund_id);
CREATE INDEX idx_refunds_idempotency_key ON refunds(idempotency_key);
CREATE INDEX idx_refunds_ledger_transaction_id ON refunds(ledger_transaction_id);

-- Add comments
COMMENT ON TABLE refunds IS 'Refund workflow state table - stores orchestration state only, not financial truth';
COMMENT ON COLUMN refunds.refund_amount_cents IS 'Total refund amount (metadata only - actual money is in ledger)';
COMMENT ON COLUMN refunds.platform_fee_refund_cents IS 'BuyIt platform commission refund (10% of refund amount)';
COMMENT ON COLUMN refunds.net_seller_refund_cents IS 'Amount refunded from seller (90% of refund amount)';
COMMENT ON COLUMN refunds.stripe_refund_id IS 'Stripe Refund ID';
COMMENT ON COLUMN refunds.ledger_transaction_id IS 'Reference to ledger transaction - NULL until refund confirmed, ledger is source of truth';
COMMENT ON COLUMN refunds.state IS 'Workflow state - REFUNDING, REFUNDED, FAILED';

