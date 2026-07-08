DROP TABLE risk.available_cash;   -- replaced by settled_cash + cash_hold

CREATE TABLE risk.settled_cash (
    account TEXT PRIMARY KEY,
    amount  BIGINT NOT NULL
);

CREATE TABLE risk.cash_hold (
    order_id      BIGINT PRIMARY KEY,
    account       TEXT   NOT NULL,
    unit_price    BIGINT NOT NULL,
    remaining_qty BIGINT NOT NULL
);

CREATE TABLE risk.processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- Seeded to match ledger openings; reconciliation guards they stay equal.
INSERT INTO risk.settled_cash (account, amount) VALUES
    ('trader1', 100000000),
    ('trader2', 100000000);
