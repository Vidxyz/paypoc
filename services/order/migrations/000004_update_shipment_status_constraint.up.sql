-- Update shipments status CHECK constraint to include all fulfillment statuses
-- Add: IN_TRANSIT, OUT_FOR_DELIVERY, RETURNED

-- Drop the old constraint
ALTER TABLE shipments DROP CONSTRAINT IF EXISTS shipments_status_check;

-- Add the new constraint with all valid statuses
ALTER TABLE shipments ADD CONSTRAINT shipments_status_check 
  CHECK (status IN ('PENDING', 'PROCESSING', 'SHIPPED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'CANCELLED', 'RETURNED'));
