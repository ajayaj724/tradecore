CREATE SCHEMA IF NOT EXISTS risk;

CREATE TABLE risk.available_cash (
    account TEXT PRIMARY KEY,
    amount  BIGINT NOT NULL CHECK (amount >= 0)  -- paise
);

CREATE TABLE risk.available_holdings (
    account  TEXT   NOT NULL,
    symbol   TEXT   NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity >= 0),
    PRIMARY KEY (account, symbol)
);

-- Seed demo balances (account = Keycloak preferred_username). Phase 2 replaces the
-- seed as source-of-truth with ledger/portfolio events; the tables stay.
INSERT INTO risk.available_cash (account, amount) VALUES
    ('trader1', 100000000),   -- ₹1,000,000.00
    ('trader2', 100000000);

INSERT INTO risk.available_holdings (account, symbol, quantity) VALUES
    ('trader1', 'ACME', 1000),
    ('trader2', 'ACME', 1000);
