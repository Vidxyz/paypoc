-- Create inventory_reservations table
-- Tracks soft and hard locks for stock
CREATE TABLE inventory_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inventory_id UUID NOT NULL REFERENCES inventory(id) ON DELETE CASCADE,
    cart_id UUID NOT NULL,  -- References shopping cart
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    reservation_type TEXT NOT NULL CHECK (reservation_type IN ('SOFT', 'HARD')),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED', 'RELEASED', 'ALLOCATED', 'SOLD')),
    expires_at TIMESTAMPTZ NOT NULL,  -- 15min for SOFT, longer for HARD
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    CONSTRAINT positive_quantity CHECK (quantity > 0)
);

-- Create indexes
CREATE INDEX idx_reservations_inventory_id ON inventory_reservations(inventory_id);
CREATE INDEX idx_reservations_cart_id ON inventory_reservations(cart_id);
CREATE INDEX idx_reservations_expires_at ON inventory_reservations(expires_at);
CREATE INDEX idx_reservations_type ON inventory_reservations(reservation_type);
CREATE INDEX idx_reservations_status ON inventory_reservations(status);

-- Add comments
COMMENT ON TABLE inventory_reservations IS 'Stock reservations table - tracks soft and hard locks';
COMMENT ON COLUMN inventory_reservations.inventory_id IS 'Reference to inventory record';
COMMENT ON COLUMN inventory_reservations.cart_id IS 'Reference to shopping cart';
COMMENT ON COLUMN inventory_reservations.quantity IS 'Quantity reserved';
COMMENT ON COLUMN inventory_reservations.reservation_type IS 'SOFT (add-to-cart, 15min) or HARD (checkout, until payment completes/fails)';
COMMENT ON COLUMN inventory_reservations.status IS 'Reservation status: ACTIVE, EXPIRED, RELEASED, ALLOCATED, SOLD';
COMMENT ON COLUMN inventory_reservations.expires_at IS 'When the reservation expires';

