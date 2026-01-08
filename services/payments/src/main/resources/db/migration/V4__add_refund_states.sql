-- Update payments table to support refund states
-- Add REFUNDING and REFUNDED states to the state constraint
ALTER TABLE payments DROP CONSTRAINT state_valid;
ALTER TABLE payments ADD CONSTRAINT state_valid CHECK (state IN ('CREATED', 'CONFIRMING', 'AUTHORIZED', 'CAPTURED', 'REFUNDING', 'REFUNDED', 'FAILED'));

-- Add refunded_at column for quick lookup
ALTER TABLE payments ADD COLUMN refunded_at TIMESTAMPTZ;

-- Add index for refunded_at
CREATE INDEX idx_payments_refunded_at ON payments(refunded_at);

-- Add comment
COMMENT ON COLUMN payments.refunded_at IS 'Timestamp when payment was refunded (NULL if not refunded)';

