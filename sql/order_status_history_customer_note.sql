-- Customer-visible status note (run once on Neon).
-- internal admin text stays in order_status_history.comments;
-- customer_note is optional and returned on public track timeline.

ALTER TABLE order_status_history
    ADD COLUMN IF NOT EXISTS customer_note TEXT;
