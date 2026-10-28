-- Neon / Postgres: add CNIC (ID card) column to shops
ALTER TABLE shops
  ADD COLUMN IF NOT EXISTS cnic VARCHAR(15);

-- Optional: index for lookups
CREATE INDEX IF NOT EXISTS idx_shops_cnic ON shops (cnic);
