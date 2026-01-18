-- Refactor payments table for order-based payments (one payment per order, multiple sellers)
-- This migration removes seller_id and adds order_id and seller_breakdown
-- Assumes no existing data in the system

-- Add new columns (nullable first, then set NOT NULL after setting defaults)
ALTER TABLE payments ADD COLUMN order_id UUID;
ALTER TABLE payments ADD COLUMN seller_breakdown JSONB;

-- Set defaults for any existing rows (should be none, but safe to do)
UPDATE payments SET order_id = gen_random_uuid() WHERE order_id IS NULL;
UPDATE payments SET seller_breakdown = '[]'::jsonb WHERE seller_breakdown IS NULL;

-- Now make them NOT NULL
ALTER TABLE payments ALTER COLUMN order_id SET NOT NULL;
ALTER TABLE payments ALTER COLUMN seller_breakdown SET NOT NULL;

-- Drop old seller_id column and its index
DROP INDEX IF EXISTS idx_payments_seller_id;
ALTER TABLE payments DROP COLUMN seller_id;

-- Drop old constraint that assumed single seller
ALTER TABLE payments DROP CONSTRAINT IF EXISTS fee_calculation;

-- Add new index for order_id
CREATE INDEX idx_payments_order_id ON payments(order_id);

-- Add new constraint: gross_amount_cents must equal sum of sellerGrossAmountCents in seller_breakdown
-- Note: This is a check constraint that validates the JSON structure
-- We'll validate this in application code, but add a basic check here
ALTER TABLE payments ADD CONSTRAINT seller_breakdown_not_empty 
    CHECK (jsonb_array_length(seller_breakdown) > 0);

-- Update comments
COMMENT ON COLUMN payments.order_id IS 'Order ID - one payment per order';
COMMENT ON COLUMN payments.seller_breakdown IS 'JSON array of seller breakdowns: [{"sellerId": "...", "sellerGrossAmountCents": 5000, "platformFeeCents": 500, "netSellerAmountCents": 4500}, ...]';
