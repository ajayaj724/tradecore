CREATE SCHEMA IF NOT EXISTS portfolio;

CREATE TABLE portfolio.position (
    account      TEXT   NOT NULL,
    symbol       TEXT   NOT NULL,
    total_qty    BIGINT NOT NULL DEFAULT 0,
    total_cost   BIGINT NOT NULL DEFAULT 0,   -- paise
    realized_pnl BIGINT NOT NULL DEFAULT 0,   -- paise
    PRIMARY KEY (account, symbol)
);

CREATE TABLE portfolio.mark_price (
    symbol TEXT PRIMARY KEY,
    price  BIGINT NOT NULL
);

CREATE TABLE portfolio.processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- Opening positions matching risk.settled_holdings (V10), valued at the opening price (10000 paise),
-- so portfolio.total_qty and risk.settled_holdings share one origin and reconcile cleanly.
INSERT INTO portfolio.position (account, symbol, total_qty, total_cost, realized_pnl) VALUES
    ('trader1', 'ACME', 1000, 10000000, 0),
    ('trader2', 'ACME', 1000, 10000000, 0);
