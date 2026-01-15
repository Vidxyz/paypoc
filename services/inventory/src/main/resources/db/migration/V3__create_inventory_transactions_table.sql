-- Create inventory_transactions table
-- Audit trail for all inventory changes
CREATE TABLE inventory_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inventory_id UUID NOT NULL REFERENCES inventory(id) ON DELETE CASCADE,
    transaction_type TEXT NOT NULL CHECK (transaction_type IN (
        'STOCK_ADD', 'STOCK_REMOVE', 'RESERVE', 'ALLOCATE', 'RELEASE', 'SELL'
    )),
    quantity INTEGER NOT NULL,
    reference_id UUID,  -- cart_id, order_id, etc.
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    CONSTRAINT non_zero_quantity CHECK (quantity != 0)
);

-- Create indexes
CREATE INDEX idx_transactions_inventory_id ON inventory_transactions(inventory_id);
CREATE INDEX idx_transactions_reference_id ON inventory_transactions(reference_id);
CREATE INDEX idx_transactions_type ON inventory_transactions(transaction_type);
CREATE INDEX idx_transactions_created_at ON inventory_transactions(created_at);

-- Add comments
COMMENT ON TABLE inventory_transactions IS 'Audit trail for all inventory changes';
COMMENT ON COLUMN inventory_transactions.inventory_id IS 'Reference to inventory record';
COMMENT ON COLUMN inventory_transactions.transaction_type IS 'Type of transaction: STOCK_ADD, STOCK_REMOVE, RESERVE, ALLOCATE, RELEASE, SELL';
COMMENT ON COLUMN inventory_transactions.quantity IS 'Quantity changed (positive or negative)';
COMMENT ON COLUMN inventory_transactions.reference_id IS 'Reference to cart_id, order_id, etc.';

