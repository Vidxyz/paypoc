-- Add refund tracking to order_items for quick state queries
ALTER TABLE order_items ADD COLUMN refunded_quantity INTEGER DEFAULT 0 CHECK (refunded_quantity >= 0);
ALTER TABLE order_items ADD CONSTRAINT refunded_quantity_check 
    CHECK (refunded_quantity <= quantity);

-- Create refunded_order_items table for audit trail
CREATE TABLE refunded_order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id UUID NOT NULL,  -- Reference to refund in payments service
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    order_item_id UUID NOT NULL REFERENCES order_items(id) ON DELETE CASCADE,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    price_cents BIGINT NOT NULL CHECK (price_cents > 0),
    seller_id TEXT NOT NULL,
    refunded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes for efficient queries
CREATE INDEX idx_refunded_order_items_refund_id ON refunded_order_items(refund_id);
CREATE INDEX idx_refunded_order_items_order_id ON refunded_order_items(order_id);
CREATE INDEX idx_refunded_order_items_order_item_id ON refunded_order_items(order_item_id);

-- Add order refund status for quick "is order fully refunded?" queries
ALTER TABLE orders ADD COLUMN refund_status TEXT DEFAULT 'NONE' 
    CHECK (refund_status IN ('NONE', 'PARTIAL', 'FULL'));

-- Add comments
COMMENT ON COLUMN order_items.refunded_quantity IS 'Quantity of this item that has been refunded';
COMMENT ON TABLE refunded_order_items IS 'Audit trail of refunded order items - tracks each refund event';
COMMENT ON COLUMN orders.refund_status IS 'Refund status: NONE, PARTIAL, or FULL';
