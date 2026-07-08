# 0008: Integer average-cost and P&L

- Status: accepted
- Date: 2026-07-08

## Context

Phase 2B computes cost basis and profit & loss for share positions. This codebase forbids floating
point for money (`BIGINT` minor units only), but average cost and realized P&L involve division, so
the position model has to keep the math integer and control where rounding happens.

## Decision

A position is stored as **`total_qty` + `total_cost`** (both `BIGINT`); average cost is *derived*
(`total_cost / total_qty`) for display only, never stored.

- **BUY:** `total_qty += qty`, `total_cost += price×qty` — exact, no rounding.
- **SELL:** cost of the shares sold is `total_cost × soldQty / total_qty` (integer division);
  `realized_pnl += price×soldQty − cost`; `total_cost −= cost`; `total_qty −= soldQty`. The rounding
  residual stays in the remaining `total_cost`, so it self-corrects and the books don't leak paise.
- **Unrealized P&L** is **division-free** and exact: `last_price × total_qty − total_cost` (market
  value minus cost basis).

This is the **average-cost** method, not FIFO/lot tracking (which would need a per-lot table and is
out of scope for the tracer). Opening demo positions are seeded to match `risk.settled_holdings`
(the holdings analogue of 2A's ledger openings), so the two reconcile.

## Consequences

- Positions and unrealized P&L are exact; the only rounding is the cost of a partial sale, bounded
  by one paise per sale and carried forward — never accumulating error in the position's value.
- Average cost is a derived read, so it can never drift from `total_cost`/`total_qty`.
- The holdings-side risk rewire (`available_holdings = settled_holdings − holds`) reuses
  [ADR-0007](0007-available-cash-settled-minus-holds.md); 2B applies that pattern to shares rather
  than restating it. Shares need no over-reservation refund (they are price-independent), so the
  sell-side hold release is exact.
- FIFO/specific-lot cost basis (for tax-lot reporting) remains available as a later, additive change.
