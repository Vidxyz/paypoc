-- Add WARNING_CLOSED state and outcome to chargebacks table
-- WARNING_CLOSED means dispute closed with warning but in merchant's favor (money returned)

ALTER TABLE chargebacks
    DROP CONSTRAINT IF EXISTS chargeback_state_valid;

ALTER TABLE chargebacks
    ADD CONSTRAINT chargeback_state_valid CHECK (state IN (
        'DISPUTE_CREATED', 'NEEDS_RESPONSE', 'UNDER_REVIEW', 
        'WON', 'LOST', 'WITHDRAWN', 'WARNING_CLOSED'
    ));

ALTER TABLE chargebacks
    DROP CONSTRAINT IF EXISTS chargeback_outcome_valid;

ALTER TABLE chargebacks
    ADD CONSTRAINT chargeback_outcome_valid CHECK (outcome IS NULL OR outcome IN ('WON', 'LOST', 'WITHDRAWN', 'WARNING_CLOSED'));

COMMENT ON COLUMN chargebacks.outcome IS 'Final outcome: WON (money returned), LOST (money debited permanently), WITHDRAWN (buyer withdrew dispute), WARNING_CLOSED (dispute closed with warning, money returned)';

