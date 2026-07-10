-- Opening INFY depth, mirroring the ACME seed (V9/V10) at INFY's opening price (150000 paise).
-- risk.settled_holdings and portfolio.position share one origin so reconciliation stays drift-0;
-- no mark_price seed (ACME has none either — the mark arrives with the first trade/feed tick).
INSERT INTO risk.settled_holdings (account, symbol, qty) VALUES
    ('trader1', 'INFY', 1000),
    ('trader2', 'INFY', 1000);

INSERT INTO portfolio.position (account, symbol, total_qty, total_cost, realized_pnl) VALUES
    ('trader1', 'INFY', 1000, 150000000, 0),
    ('trader2', 'INFY', 1000, 150000000, 0);
