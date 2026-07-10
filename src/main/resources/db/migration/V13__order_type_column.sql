-- Persist the order type so the API can echo it; legacy rows predate MARKET support.
ALTER TABLE orders.trade_order ADD COLUMN order_type TEXT NOT NULL DEFAULT 'LIMIT';
