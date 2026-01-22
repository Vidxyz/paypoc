-- Revert to original constraint (without IN_TRANSIT, OUT_FOR_DELIVERY, RETURNED)

-- Drop the new constraint
ALTER TABLE shipments DROP CONSTRAINT IF EXISTS shipments_status_check;

-- Restore the original constraint
ALTER TABLE shipments ADD CONSTRAINT shipments_status_check 
  CHECK (status IN ('PENDING', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED'));
