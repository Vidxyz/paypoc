-- Create chargebacks table
-- Chargebacks (disputes) are initiated by the buyer's bank, not the merchant.
-- They have a complex lifecycle with evidence submission and can result in won/lost outcomes.

CREATE TABLE chargebacks (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(id),
    chargeback_amount_cents BIGINT NOT NULL,
    dispute_fee_cents BIGINT NOT NULL,
    currency TEXT NOT NULL,
    state TEXT NOT NULL,
    stripe_dispute_id TEXT UNIQUE NOT NULL,
    stripe_charge_id TEXT NOT NULL,
    reason TEXT,
    evidence_due_by TIMESTAMPTZ,
    ledger_transaction_id UUID,
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at TIMESTAMPTZ,
    outcome TEXT,
    
    CONSTRAINT chargeback_state_valid CHECK (state IN (
        'DISPUTE_CREATED', 'NEEDS_RESPONSE', 'UNDER_REVIEW', 
        'WON', 'LOST', 'WITHDRAWN'
    )),
    CONSTRAINT chargeback_outcome_valid CHECK (outcome IS NULL OR outcome IN ('WON', 'LOST', 'WITHDRAWN')),
    CONSTRAINT chargeback_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chargeback_positive_amount CHECK (chargeback_amount_cents > 0),
    CONSTRAINT chargeback_non_negative_fee CHECK (dispute_fee_cents >= 0)
);

CREATE INDEX idx_chargebacks_payment_id ON chargebacks(payment_id);
CREATE INDEX idx_chargebacks_stripe_dispute_id ON chargebacks(stripe_dispute_id);
CREATE INDEX idx_chargebacks_state ON chargebacks(state);

COMMENT ON TABLE chargebacks IS 'Chargebacks (disputes) initiated by buyer banks. Money is debited immediately when dispute is created.';
COMMENT ON COLUMN chargebacks.chargeback_amount_cents IS 'Amount disputed (can be partial, less than or equal to payment amount)';
COMMENT ON COLUMN chargebacks.dispute_fee_cents IS 'Stripe dispute fee ($15-25, charged even if platform wins)';
COMMENT ON COLUMN chargebacks.evidence_due_by IS 'Deadline to submit evidence (typically ~7 days from creation)';
COMMENT ON COLUMN chargebacks.ledger_transaction_id IS 'NULL until dispute created (money debited immediately)';
COMMENT ON COLUMN chargebacks.outcome IS 'Final outcome: WON (money returned), LOST (money debited permanently), WITHDRAWN (buyer withdrew dispute)';

