-- Drop indexes
DROP INDEX IF EXISTS idx_shipments_status;
DROP INDEX IF EXISTS idx_shipments_seller_id;
DROP INDEX IF EXISTS idx_shipments_order_id;
DROP INDEX IF EXISTS idx_order_items_seller_id;
DROP INDEX IF EXISTS idx_order_items_order_id;
DROP INDEX IF EXISTS idx_orders_payment_id;
DROP INDEX IF EXISTS idx_orders_status;
DROP INDEX IF EXISTS idx_orders_buyer_id;

-- Drop tables (order matters due to foreign keys)
DROP TABLE IF EXISTS shipments;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
