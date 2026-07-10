# 0023: ADMIN as the observe-everything role

- Status: accepted
- Date: 2026-07-10

## Context

`admin1` carried the ADMIN realm role with no assigned semantics: it saw a trader's empty
screen. Meanwhile the reconciliation module (ADR-0009) computed drift and per-account
equity but published them only as Prometheus gauges — invisible without Grafana.

## Decision

**ADMIN observes; OPS operates; TRADER trades.**

- ADMIN gains **read parity with OPS**: every GET (orders incl. `scope=all`, balances,
  positions, instruments) accepts TRADER/OPS/ADMIN. Mutations stay unchanged — submit is
  TRADER-only, cancel is TRADER/OPS — so ADMIN can see everything and touch nothing.
  Rejected: ADMIN ⊇ OPS (cancel rights too) — a role that can both audit and act weakens
  both functions; separation is the point of having three roles.
- New `GET /api/v1/reconciliation` (ADMIN-only) returns the same computation the scheduled
  gauges publish — `report()` is now the single code path for both, so the API and Grafana
  can never disagree. Structure: total drifted pairs + per-account equity/cash-drift.
- The web app shows admins a **System health panel** (drift chip + equity table, 60s
  refresh matching the reconciler cadence) above an all-accounts blotter with no cancel
  actions.

## Consequences

- Every seeded persona now has a distinct, demonstrable purpose in the UI.
- The reconciliation module gains its first HTTP surface; its module boundary is
  unchanged (same fan-in reads, ADR-0009).
