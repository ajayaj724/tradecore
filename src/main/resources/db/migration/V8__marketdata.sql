CREATE SCHEMA IF NOT EXISTS marketdata;

CREATE TABLE marketdata.last_price (
    symbol TEXT PRIMARY KEY,
    price  BIGINT NOT NULL
);

CREATE TABLE marketdata.processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

INSERT INTO marketdata.last_price (symbol, price) VALUES ('ACME', 10000), ('INFY', 150000);
