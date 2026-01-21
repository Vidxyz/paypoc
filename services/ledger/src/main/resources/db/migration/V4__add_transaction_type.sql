-- Add transaction_type column to ledger_transactions
-- This allows multiple transactions per external reference (e.g., one per chargeback event type)

-- Step 1: Add column (nullable initially for backfilling)
ALTER TABLE ledger_transactions 
ADD COLUMN transaction_type TEXT;

-- Step 2: Backfill existing rows based on description patterns
-- Payment captured events
UPDATE ledger_transactions 
SET transaction_type = 'PAYMENT_CAPTURED' 
WHERE description LIKE '%Payment captured%' 
   OR description LIKE '%payment captured%'
   OR description LIKE '%PAYMENT_CAPTURED%';

-- Refund completed events
UPDATE ledger_transactions 
SET transaction_type = 'REFUND_COMPLETED' 
WHERE description LIKE '%Refund%' 
   OR description LIKE '%refund%'
   OR description LIKE '%REFUND_COMPLETED%';

-- Chargeback created events
UPDATE ledger_transactions 
SET transaction_type = 'CHARGEBACK_CREATED' 
WHERE description LIKE '%Chargeback created%' 
   OR description LIKE '%chargeback created%'
   OR description LIKE '%CHARGEBACK_CREATED%';

-- Chargeback lost events
UPDATE ledger_transactions 
SET transaction_type = 'CHARGEBACK_LOST' 
WHERE description LIKE '%Chargeback lost%' 
   OR description LIKE '%chargeback lost%'
   OR description LIKE '%CHARGEBACK_LOST%';

-- Chargeback won events
UPDATE ledger_transactions 
SET transaction_type = 'CHARGEBACK_WON' 
WHERE description LIKE '%Chargeback won%' 
   OR description LIKE '%chargeback won%'
   OR description LIKE '%CHARGEBACK_WON%';

-- Chargeback warning closed events
UPDATE ledger_transactions 
SET transaction_type = 'CHARGEBACK_WARNING_CLOSED' 
WHERE description LIKE '%warning_closed%' 
   OR description LIKE '%WARNING_CLOSED%'
   OR description LIKE '%warning closed%';

-- Payout completed events
UPDATE ledger_transactions 
SET transaction_type = 'PAYOUT_COMPLETED' 
WHERE description LIKE '%Payout%' 
   OR description LIKE '%payout%'
   OR description LIKE '%PAYOUT_COMPLETED%';

-- User created events
UPDATE ledger_transactions 
SET transaction_type = 'USER_CREATED' 
WHERE description LIKE '%User created%' 
   OR description LIKE '%user created%'
   OR description LIKE '%USER_CREATED%';

-- Set default for any remaining NULL values (shouldn't happen, but safety)
UPDATE ledger_transactions 
SET transaction_type = 'UNKNOWN' 
WHERE transaction_type IS NULL;

-- Step 3: Make column NOT NULL
ALTER TABLE ledger_transactions 
ALTER COLUMN transaction_type SET NOT NULL;

-- Step 4: Drop old unique constraint on reference_id
ALTER TABLE ledger_transactions 
DROP CONSTRAINT IF EXISTS ledger_transactions_reference_id_key;

-- Step 5: Add composite unique constraint on (reference_id, transaction_type)
ALTER TABLE ledger_transactions 
ADD CONSTRAINT ledger_transactions_reference_id_type_key 
UNIQUE (reference_id, transaction_type);

-- Add index for querying by transaction_type
CREATE INDEX idx_ledger_transactions_transaction_type ON ledger_transactions(transaction_type);

-- Update comment
COMMENT ON COLUMN ledger_transactions.transaction_type IS 'Type of transaction (e.g., PAYMENT_CAPTURED, CHARGEBACK_CREATED, CHARGEBACK_LOST). Allows multiple transactions per external reference.';
