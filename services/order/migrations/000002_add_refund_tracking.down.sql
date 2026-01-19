-- Rollback refund tracking changes
ALTER TABLE orders DROP COLUMN IF EXISTS refund_status;
DROP TABLE IF EXISTS refunded_order_items;
ALTER TABLE order_items DROP CONSTRAINT IF EXISTS refunded_quantity_check;
ALTER TABLE order_items DROP COLUMN IF EXISTS refunded_quantity;
