-- Create reconciliation_runs table
-- Stores reconciliation run metadata for audit trail
CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY,
    start_date TIMESTAMPTZ NOT NULL,
    end_date TIMESTAMPTZ NOT NULL,
    currency TEXT,  -- NULL means all currencies
    run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    matched_transactions INTEGER NOT NULL DEFAULT 0,
    total_stripe_transactions INTEGER NOT NULL DEFAULT 0,
    total_ledger_transactions INTEGER NOT NULL DEFAULT 0,
    total_discrepancies INTEGER NOT NULL DEFAULT 0,
    missing_in_ledger_count INTEGER NOT NULL DEFAULT 0,
    missing_in_stripe_count INTEGER NOT NULL DEFAULT 0,
    amount_mismatches_count INTEGER NOT NULL DEFAULT 0,
    currency_mismatches_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    CONSTRAINT currency_format CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT valid_date_range CHECK (start_date <= end_date),
    CONSTRAINT non_negative_counts CHECK (
        matched_transactions >= 0 AND
        total_stripe_transactions >= 0 AND
        total_ledger_transactions >= 0 AND
        total_discrepancies >= 0 AND
        missing_in_ledger_count >= 0 AND
        missing_in_stripe_count >= 0 AND
        amount_mismatches_count >= 0 AND
        currency_mismatches_count >= 0
    )
);

-- Create reconciliation_discrepancies table
-- Stores individual discrepancies found during reconciliation runs
CREATE TABLE reconciliation_discrepancies (
    id UUID PRIMARY KEY,
    reconciliation_run_id UUID NOT NULL REFERENCES reconciliation_runs(id) ON DELETE CASCADE,
    type TEXT NOT NULL,
    stripe_transaction_id TEXT,
    ledger_transaction_id UUID,
    stripe_amount BIGINT,
    ledger_amount BIGINT,
    currency TEXT NOT NULL,
    description TEXT NOT NULL,
    severity TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    CONSTRAINT discrepancy_type_valid CHECK (type IN (
        'MISSING_IN_LEDGER', 'MISSING_IN_STRIPE', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH'
    )),
    CONSTRAINT severity_valid CHECK (severity IN (
        'CRITICAL', 'HIGH', 'MEDIUM', 'LOW'
    )),
    CONSTRAINT currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT at_least_one_transaction_id CHECK (
        stripe_transaction_id IS NOT NULL OR ledger_transaction_id IS NOT NULL
    )
);

-- Create indexes for efficient querying
CREATE INDEX idx_reconciliation_runs_run_at ON reconciliation_runs(run_at DESC);
CREATE INDEX idx_reconciliation_runs_start_date ON reconciliation_runs(start_date);
CREATE INDEX idx_reconciliation_runs_end_date ON reconciliation_runs(end_date);
CREATE INDEX idx_reconciliation_runs_currency ON reconciliation_runs(currency) WHERE currency IS NOT NULL;

CREATE INDEX idx_reconciliation_discrepancies_run_id ON reconciliation_discrepancies(reconciliation_run_id);
CREATE INDEX idx_reconciliation_discrepancies_type ON reconciliation_discrepancies(type);
CREATE INDEX idx_reconciliation_discrepancies_severity ON reconciliation_discrepancies(severity);
CREATE INDEX idx_reconciliation_discrepancies_stripe_tx_id ON reconciliation_discrepancies(stripe_transaction_id) WHERE stripe_transaction_id IS NOT NULL;
CREATE INDEX idx_reconciliation_discrepancies_ledger_tx_id ON reconciliation_discrepancies(ledger_transaction_id) WHERE ledger_transaction_id IS NOT NULL;

