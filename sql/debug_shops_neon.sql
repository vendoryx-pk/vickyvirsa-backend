-- ============================================================
-- VendoryX Neon DB — Shops / Cities / Visit Days debug + seed
-- Run these in Neon SQL Editor (one section at a time)
-- ============================================================

-- 1) Do the tables exist?
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('cities', 'visit_days', 'shops', 'pages', 'roles', 'role_pages', 'users')
ORDER BY table_name;

-- 2) Row counts
SELECT 'cities' AS table_name, COUNT(*) AS rows FROM cities
UNION ALL
SELECT 'visit_days', COUNT(*) FROM visit_days
UNION ALL
SELECT 'shops', COUNT(*) FROM shops
UNION ALL
SELECT 'pages', COUNT(*) FROM pages;

-- 3) Visit days (Mon–Sun should appear)
SELECT day_id, day_name, day_order
FROM visit_days
ORDER BY day_order;

-- 4) Cities
SELECT city_id, city_name
FROM cities
ORDER BY city_name;

-- 5) Shops joined like the backend API
SELECT
  s.shop_id,
  s.shop_code,
  s.shop_name,
  s.contact_person,
  s.phone,
  c.city_name,
  d.day_name,
  s.latitude,
  s.longitude,
  s.status
FROM shops s
JOIN cities c ON s.city_id = c.city_id
JOIN visit_days d ON s.day_id = d.day_id
ORDER BY s.shop_name;

-- 6) Pages linked to roles (sidebar /shops)
SELECT p.page_id, p.page_name, p.route_path, p.icon, r.role_name
FROM pages p
LEFT JOIN role_pages rp ON rp.page_id = p.page_id
LEFT JOIN roles r ON r.role_id = rp.role_id
ORDER BY p.page_name, r.role_name;

-- ============================================================
-- SEED (safe to re-run) — only if tables exist but are empty
-- ============================================================

-- Visit days
INSERT INTO visit_days (day_name, day_order)
SELECT v.day_name, v.day_order
FROM (VALUES
  ('Monday', 1),
  ('Tuesday', 2),
  ('Wednesday', 3),
  ('Thursday', 4),
  ('Friday', 5),
  ('Saturday', 6),
  ('Sunday', 7)
) AS v(day_name, day_order)
WHERE NOT EXISTS (
  SELECT 1 FROM visit_days d WHERE d.day_name = v.day_name
);

-- Sample cities (edit names if you want)
INSERT INTO cities (city_name)
SELECT v.city_name
FROM (VALUES
  ('Karachi'),
  ('Lahore'),
  ('Islamabad')
) AS v(city_name)
WHERE NOT EXISTS (
  SELECT 1 FROM cities c WHERE c.city_name = v.city_name
);

-- Sample shop (needs at least 1 city + 1 day)
INSERT INTO shops (
  shop_code, shop_name, contact_person, phone, address,
  latitude, longitude, city_id, day_id, status
)
SELECT
  'SHP-001',
  'Demo Mart',
  'Ali Khan',
  '03001234567',
  'Main Boulevard',
  24.8607,
  67.0011,
  (SELECT city_id FROM cities ORDER BY city_id LIMIT 1),
  (SELECT day_id FROM visit_days ORDER BY day_order LIMIT 1),
  'ACTIVE'
WHERE NOT EXISTS (
  SELECT 1 FROM shops WHERE shop_code = 'SHP-001'
);

-- Ensure /shops page exists for sidebar RBAC
INSERT INTO pages (page_name, route_path, icon)
SELECT 'Shops', '/shops', 'store'
WHERE NOT EXISTS (
  SELECT 1 FROM pages WHERE route_path = '/shops'
);

-- Link Shops page to DEVELOPER role (adjust role_name if different)
INSERT INTO role_pages (role_id, page_id)
SELECT r.role_id, p.page_id
FROM roles r
CROSS JOIN pages p
WHERE UPPER(r.role_name) = 'DEVELOPER'
  AND p.route_path = '/shops'
  AND NOT EXISTS (
    SELECT 1
    FROM role_pages rp
    WHERE rp.role_id = r.role_id
      AND rp.page_id = p.page_id
  );

-- ============================================================
-- If tables are MISSING, create them (Postgres / Neon)
-- ============================================================

/*
CREATE TABLE IF NOT EXISTS cities (
  city_id   SERIAL PRIMARY KEY,
  city_name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS visit_days (
  day_id    SERIAL PRIMARY KEY,
  day_name  VARCHAR(20) NOT NULL UNIQUE,
  day_order INT NOT NULL
);

CREATE TABLE IF NOT EXISTS shops (
  shop_id         SERIAL PRIMARY KEY,
  shop_code       VARCHAR(40) NOT NULL UNIQUE,
  shop_name       VARCHAR(120) NOT NULL,
  contact_person  VARCHAR(120),
  phone           VARCHAR(40),
  address         TEXT,
  latitude        DOUBLE PRECISION,
  longitude       DOUBLE PRECISION,
  city_id         INT NOT NULL REFERENCES cities(city_id),
  day_id          INT NOT NULL REFERENCES visit_days(day_id),
  status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at      TIMESTAMP DEFAULT NOW()
);
*/
