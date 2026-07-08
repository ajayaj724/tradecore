DROP TABLE risk.available_holdings;   -- replaced by settled_holdings + holdings_hold

CREATE TABLE risk.settled_holdings (
    account TEXT   NOT NULL,
    symbol  TEXT   NOT NULL,
    qty     BIGINT NOT NULL,
    PRIMARY KEY (account, symbol)
);

CREATE TABLE risk.holdings_hold (
    order_id      BIGINT PRIMARY KEY,
    account       TEXT   NOT NULL,
    symbol        TEXT   NOT NULL,
    remaining_qty BIGINT NOT NULL
);

INSERT INTO risk.settled_holdings (account, symbol, qty) VALUES
    ('trader1', 'ACME', 1000),
    ('trader2', 'ACME', 1000);
