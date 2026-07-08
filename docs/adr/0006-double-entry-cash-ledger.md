# 0006: Double-entry cash ledger — postings only, balances derived

- Status: accepted
- Date: 2026-07-08

## Context

Phase 2A makes a filled trade move real cash between the buyer and the seller. That cash movement
must be auditable, impossible to create or destroy by accident, and reconcilable against risk's
view and (in 2C) against positions.

## Decision

`ledger` stores **signed postings**, never mutable balances. A trade writes two postings sharing a
`txn_id` — `−notional` for the buyer's cash account and `+notional` for the seller's — so every
transaction's postings sum to zero. An account's cash balance is `Σ amount` over its postings,
computed on read. Opening balances are themselves balanced postings (credit each trader, debit a
`house` capital account), not a magic starting number. Phase 2A is a **direct buyer↔seller
transfer** — no fees or clearing account (v1 has neither). Posting is idempotent (dedup on the
trade's event id).

## Consequences

- Cash is conserved: the system-wide sum of all postings is zero, and it stays zero after every
  trade — a directly testable invariant.
- Every cash movement is an immutable, auditable row; there is no "current balance" to corrupt.
- Balances are derived, so a reporting/read-model layer (risk's `settled_cash`, 2C reconciliation)
  is a projection, never the source of truth.
- Fees, taxes, and a clearing/settlement account are deferred to a later phase; they slot in as
  additional postings within the same balanced transaction.
