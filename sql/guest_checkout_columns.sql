-- Guest B2C checkout columns (run once on Neon).
-- Keeps existing B2B shop orders working; only adds nullable guest fields.

-- orders: allow guest (no shop / no logged-in placer)
ALTER TABLE orders ALTER COLUMN shop_id DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN placed_by_user_id DROP NOT NULL;

ALTER TABLE orders ADD COLUMN IF NOT EXISTS customer_name VARCHAR(120);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS customer_phone VARCHAR(30);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS customer_address TEXT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS customer_city VARCHAR(80);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_method VARCHAR(20);

-- order_items: product name snapshot at purchase (nullable so B2B inserts unchanged)
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS product_name VARCHAR(200);
