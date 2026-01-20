-- Remove index
DROP INDEX IF EXISTS idx_orders_cart_id;

-- Remove cart_id column
ALTER TABLE orders DROP COLUMN IF EXISTS cart_id;
