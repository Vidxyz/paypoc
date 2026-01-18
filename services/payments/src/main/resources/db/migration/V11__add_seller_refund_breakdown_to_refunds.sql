-- Add seller_refund_breakdown to refunds table for partial refunds
-- This stores which sellers are being refunded and how much per seller
-- Format: [{"sellerId": "seller1", "refundAmountCents": 5000, "platformFeeRefundCents": 500, "netSellerRefundCents": 4500}, ...]

ALTER TABLE refunds ADD COLUMN seller_refund_breakdown JSONB;

-- Set default for existing rows (should be none, but safe to do)
UPDATE refunds SET seller_refund_breakdown = '[]'::jsonb WHERE seller_refund_breakdown IS NULL;

-- Add comment
COMMENT ON COLUMN refunds.seller_refund_breakdown IS 'JSON array of seller refund breakdowns for partial refunds: [{"sellerId": "...", "refundAmountCents": 5000, "platformFeeRefundCents": 500, "netSellerRefundCents": 4500}, ...]';
