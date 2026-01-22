-- Add delivery details columns to orders table
ALTER TABLE orders ADD COLUMN delivery_full_name TEXT;
ALTER TABLE orders ADD COLUMN delivery_address TEXT;
ALTER TABLE orders ADD COLUMN delivery_city TEXT;
ALTER TABLE orders ADD COLUMN delivery_province TEXT;
ALTER TABLE orders ADD COLUMN delivery_postal_code TEXT;
ALTER TABLE orders ADD COLUMN delivery_country TEXT DEFAULT 'Canada';
ALTER TABLE orders ADD COLUMN delivery_phone TEXT;

-- Add comments
COMMENT ON COLUMN orders.delivery_full_name IS 'Full name for delivery';
COMMENT ON COLUMN orders.delivery_address IS 'Street address for delivery';
COMMENT ON COLUMN orders.delivery_city IS 'City for delivery';
COMMENT ON COLUMN orders.delivery_province IS 'Province/State for delivery';
COMMENT ON COLUMN orders.delivery_postal_code IS 'Postal/ZIP code for delivery';
COMMENT ON COLUMN orders.delivery_country IS 'Country for delivery';
COMMENT ON COLUMN orders.delivery_phone IS 'Phone number for delivery (optional)';
