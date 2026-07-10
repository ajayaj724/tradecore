# 0019: Cash balances read API served by the risk module

- Status: accepted
- Date: 2026-07-10

## Context

The web UI needs a live cash figure (available / reserved). Settled cash truth is the
ledger's posting sum, but "available" also needs the active order holds — and those live
only in the risk module's reservation tables (`risk.settled_cash` projection +
`risk.cash_hold`), per ADR-0007 (available = settled − holds).

## Decision

`GET /api/v1/balances` is served **by the risk module** (`BalanceController`, package-private,
returning `CashBalance{settled, held, available}` in paise). Rejected: serving it from the
ledger module — it has settled postings but would need risk's holds, forcing a cross-module
read that the module boundaries forbid; an aggregate at the app layer was likewise rejected
because risk already owns exactly this computation for its pre-trade check (`reserveCash`),
so the endpoint reuses one source of truth instead of duplicating the arithmetic.

The endpoint returns the caller's own balance (`preferred_username`), TRADER/OPS roles.
All figures are `long` paise; subtraction only, display conversion at the UI edge.

## Consequences

- The UI's "Reserved" tile now shows the risk module's actual holds instead of a
  client-side estimate; discrepancies between the two would have hidden real bugs.
- Reconciliation (ADR-0009) continues to guard ledger-vs-risk settled equality, which is
  what makes serving "settled" from the risk projection honest.
