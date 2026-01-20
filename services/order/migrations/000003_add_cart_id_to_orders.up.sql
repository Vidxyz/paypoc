-- Add cart_id column to orders table
ALTER TABLE orders ADD COLUMN cart_id UUID;

-- Create index for cart_id lookups
CREATE INDEX idx_orders_cart_id ON orders(cart_id);

-- Add comment
COMMENT ON COLUMN orders.cart_id IS 'Reference to cart that this order was created from';
