# --- !Ups

-- Shopping carts (persistent storage for completed/abandoned carts)
CREATE TABLE carts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_id TEXT NOT NULL,  -- From Auth0 token (user_id)
    status TEXT NOT NULL CHECK (status IN ('CHECKOUT', 'COMPLETED', 'ABANDONED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    
    INDEX idx_buyer (buyer_id),
    INDEX idx_status (status),
    INDEX idx_completed (completed_at)
);

-- Cart items (snapshot at checkout/completion)
CREATE TABLE cart_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id UUID NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    product_id UUID NOT NULL,
    sku TEXT NOT NULL,
    seller_id TEXT NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    price_cents BIGINT NOT NULL,
    currency TEXT NOT NULL,
    reservation_id UUID,  -- References inventory_reservations
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    INDEX idx_cart (cart_id),
    INDEX idx_reservation (reservation_id)
);

# --- !Downs

DROP INDEX IF EXISTS idx_cart;
DROP INDEX IF EXISTS idx_reservation;
DROP TABLE IF EXISTS cart_items;
DROP INDEX IF EXISTS idx_buyer;
DROP INDEX IF EXISTS idx_status;
DROP INDEX IF EXISTS idx_completed;
DROP TABLE IF EXISTS carts;

