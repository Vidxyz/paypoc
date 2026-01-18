-- Add order_items_refunded JSONB column to store which order items were refunded
-- This is used for tracking refunds at the order item level
ALTER TABLE refunds ADD COLUMN order_items_refunded JSONB;

-- Add comment
COMMENT ON COLUMN refunds.order_items_refunded IS 'JSON array of refunded order items: [{"orderItemId": "...", "quantity": 2, "sellerId": "...", "priceCents": 5000}, ...]';
