-- Remove delivery details columns from orders table
ALTER TABLE orders DROP COLUMN IF EXISTS delivery_full_name;
ALTER TABLE orders DROP COLUMN IF EXISTS delivery_address;
ALTER TABLE orders DROP COLUMN IF EXISTS delivery_city;
ALTER TABLE orders DROP COLUMN IF EXISTS delivery_province;
ALTER TABLE orders DROP COLUMN IF EXISTS delivery_postal_code;
ALTER TABLE orders DROP COLUMN IF EXISTS delivery_country;
ALTER TABLE orders DROP COLUMN IF EXISTS delivery_phone;
