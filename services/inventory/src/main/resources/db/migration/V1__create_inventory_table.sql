-- Create inventory table
-- Tracks stock levels with available, reserved, and allocated quantities
-- Uses SERIALIZABLE isolation with optimistic locking (version column) for concurrency control
CREATE TABLE inventory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL,  -- References catalog service (eventual consistency)
    seller_id TEXT NOT NULL,
    sku TEXT NOT NULL,
    available_quantity INTEGER NOT NULL DEFAULT 0 CHECK (available_quantity >= 0),
    reserved_quantity INTEGER NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    allocated_quantity INTEGER NOT NULL DEFAULT 0 CHECK (allocated_quantity >= 0),
    total_quantity INTEGER NOT NULL CHECK (total_quantity >= 0),
    low_stock_threshold INTEGER DEFAULT 10,
    version BIGINT NOT NULL DEFAULT 0,  -- Optimistic locking version
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    UNIQUE(seller_id, sku),
    CONSTRAINT non_negative_available CHECK (available_quantity >= 0),
    CONSTRAINT non_negative_reserved CHECK (reserved_quantity >= 0),
    CONSTRAINT non_negative_allocated CHECK (allocated_quantity >= 0),
    CONSTRAINT non_negative_total CHECK (total_quantity >= 0),
    CONSTRAINT quantity_sum_check CHECK (available_quantity + reserved_quantity + allocated_quantity <= total_quantity)
);

-- Create indexes
CREATE INDEX idx_inventory_product_id ON inventory(product_id);
CREATE INDEX idx_inventory_seller_sku ON inventory(seller_id, sku);

-- Add comments
COMMENT ON TABLE inventory IS 'Inventory tracking table - tracks available, reserved, and allocated quantities per product';
COMMENT ON COLUMN inventory.product_id IS 'Reference to product in catalog service';
COMMENT ON COLUMN inventory.seller_id IS 'Seller identifier';
COMMENT ON COLUMN inventory.sku IS 'Stock keeping unit (unique per seller)';
COMMENT ON COLUMN inventory.available_quantity IS 'Quantity available for reservation';
COMMENT ON COLUMN inventory.reserved_quantity IS 'Quantity soft-reserved in carts (can be reclaimed)';
COMMENT ON COLUMN inventory.allocated_quantity IS 'Quantity hard-locked for checkout (cannot be reclaimed until timeout)';
COMMENT ON COLUMN inventory.total_quantity IS 'Total stock quantity';
COMMENT ON COLUMN inventory.low_stock_threshold IS 'Threshold for low stock alerts';
COMMENT ON COLUMN inventory.version IS 'Optimistic locking version for concurrency control';

