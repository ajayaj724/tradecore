-- Reference prices projected from marketdata's PriceUpdated events; risk uses them to
-- derive the protective collar cap for unpriced MARKET orders (ADR-0021).
CREATE TABLE risk.reference_price (
    symbol     TEXT PRIMARY KEY,
    price      BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Seeded to match marketdata's V8 openings, same pattern as settled_cash vs the ledger.
INSERT INTO risk.reference_price (symbol, price, updated_at) VALUES
    ('ACME', 10000, now()),
    ('INFY', 150000, now());
