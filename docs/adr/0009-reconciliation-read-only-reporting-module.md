# 0009: Reconciliation as a read-only reporting module

- Status: accepted
- Date: 2026-07-08

## Context

The design spec makes `orders -> risk` the only synchronous cross-module call on the trading
**write** path. Phase 2C adds a `reconciliation` job that must compare risk's settled projections
against the ledger/portfolio source of truth — which means reading settled state across several
modules. Taken literally, the "only `orders -> risk` sync" rule would forbid this.

## Decision

Permit `reconciliation` to call the **public read APIs** of `ledger`, `portfolio`, `risk`, and
`marketdata` (`balanceOf`, `positionQty`, `settledCash`, `settledHoldings`, `lastPrice`). It reads
no other module's tables. The write-path rule governs the trading path; read-only reporting is a
distinct category. `reconciliation` is pure fan-in — nothing depends on it — so
`ApplicationModules.verify()` stays acyclic. The rejected alternative was every module
re-publishing snapshot events for a batch job, which is machinery the tracer does not need.

## Consequences

- `reconciliation` shows up as depending on four modules in the generated architecture docs; this is
  intended and bounded to read methods.
- Reconciliation detects drift from a lost/duplicated event; it does not remediate (operator work).
- If the write-path sync rule is ever tightened to "no cross-module sync at all," this ADR is the
  documented exception for reporting reads.
