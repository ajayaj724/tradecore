# tradecore Phase 2A — Ledger & Cash Settlement: Design Spec

- **Date:** 2026-07-08
- **Status:** Approved (brainstorming) — pending implementation plan
- **Extends:** [`2026-07-06-brokerage-oms-design.md`](2026-07-06-brokerage-oms-design.md) §10 Phase 2 (slice 2A of 3)
- **Builds on:** Phase 1B (the tracer bullet) — `orders`, `risk`, `execution`, the engine, `shared`
- **Settles the debt from:** [ADR-0003](../../adr/0003-risk-seeded-read-model-projection.md) — risk's seeded
  `available_cash` becomes event-fed by ledger (cash half; holdings half is slice 2B)
- **Branch:** `feat/phase2a-ledger-cash`

## 1. Purpose & scope

Phase 2 ("the money story") decomposes into three vertical slices, each its own spec → plan →
implementation. This is **slice 2A**: a filled trade now moves *real* settled cash between the
buyer and the seller through a double-entry `ledger`, and `risk`'s buy-side check runs off a
**ledger-fed settled balance minus live reservation holds** instead of a standalone seeded number.

The follow-on slices (own specs): **2B** — `marketdata` + `portfolio` (positions, P&L) + the
holdings-side risk rewire; **2C** — the end-of-day reconciliation job + fleshed-out Grafana
dashboards.

### 1.1 In scope

- New **`ledger`** module: double-entry cash postings, every transaction balances to zero,
  opening deposits seeded as balanced postings, cash balance = `Σ postings`.
- **Enrich `TradeExecuted`** with `buyerAccount` + `sellerAccount`; `execution` stamps them from an
  `orderId → account` projection it builds from `OrderAccepted`.
- Refactor `risk`'s **cash** side to `available = settled − Σ holds`: a ledger-fed `settled_cash`
  read-model plus risk-owned reservation `cash_hold`s (placed on accept, released on fill).
- Three new idempotent listeners, each with a duplicate-delivery test.

### 1.2 Out of scope (this slice)

- **Holdings-side risk** — sells still check the seeded `available_holdings` (decrement-on-accept,
  as in 1B). Rewired in 2B once `portfolio` exists.
- **`portfolio`, `marketdata`, positions, P&L, prices** — slice 2B.
- **Reconciliation job + dashboards** — slice 2C (2A ships only a reconciliation-*lite* IT).
- **Fees, clearing accounts, cancels/amends** — v1-out or later phases; 2A is a direct
  buyer↔seller cash transfer.

## 2. Decisions locked in brainstorming

| # | Fork | Decision | Rationale |
|---|---|---|---|
| 1 | Cash model | **`available = settled − holds`** | Honors ADR-0003: settled cash is ledger-owned/ledger-fed; holds are risk's pre-trade concern. Models the real available-vs-settled distinction a single number cannot |
| 2 | Account resolution | **Enrich `TradeExecuted`** (execution stamps buyer/seller accounts) | DRY for every downstream consumer (ledger now, portfolio in 2B, reconciliation, future Kafka externalization); execution already consumes `OrderAccepted` so it can retain `orderId → account` |
| 3 | Cash movement shape | **Direct buyer↔seller transfer** (2 postings, Σ = 0) | v1 has no fees or clearing; a direct transfer satisfies double-entry. Clearing/fee accounts are a later phase |
| 4 | Initial balances | **Seed `risk.settled_cash` via Flyway to match ledger openings** | Deterministic and simple vs. replaying opening events at startup; reconciliation guards that they stay equal |
| 5 | Settled vs. hold-release delivery | **Two focused listeners** (`CashPosted` → settled; `TradeExecuted` → hold release), eventually consistent | Keeps event contracts clean (a cash event is a pure cash fact); the brief window is reconciliation-guarded. Alternative — a fat `BuyerSettled` event carrying orderId+qty for atomic update — rejected as event-coupling |

## 3. Module structure & the cash flow

```
POST buy → OrderService (1B, one transaction)
   └─ risk.check(orderId, account, BUY, price, qty)          ← now takes orderId
        available = settled_cash[account] − Σ cash_hold[account]
        if available ≥ price×qty → INSERT cash_hold(orderId, account, unit=price, remaining_qty=qty)

⇢ execution matches → publishes TradeExecuted{ …, buyerAccount, sellerAccount }
   ├─ ledger  consumes TradeExecuted (idempotent)
   │     → one balanced transaction: DEBIT buyer cash (−fill×qty), CREDIT seller cash (+fill×qty)
   │     → publishes CashPosted(account, amount) ×2   (amount = signed posting delta)
   │           └─ risk consumes CashPosted (idempotent, dedup on event id) → settled_cash[account] += amount
   └─ risk    consumes TradeExecuted (idempotent)
         → release cash_hold(buyOrderId): remaining_qty −= fillQty  (hold amount = unit×remaining_qty)
```

- `orders → risk` stays the only synchronous cross-module call; `risk.check` gains an `orderId`
  argument so the hold can be keyed to the order it reserves for.
- `ledger` depends on `shared` only; it never reads another module's tables.
- `risk` gains two `@ApplicationModuleListener`s; `ledger` gains one. Every new listener is
  idempotent (dedup on event id) and ships a duplicate-delivery test.

## 4. Ledger — double-entry cash

- **`ledger.posting`**: `(id, txn_id, account, amount BIGINT signed, kind, created_at)`. A trade is
  one `txn_id` with two postings whose `amount`s sum to zero (debit buyer, credit seller). Cash
  balance for an account is `Σ amount` over its postings — never a mutable balance column.
- **Opening deposits**: seeded as balanced postings — credit each demo trader's cash, debit a
  `house` capital account — for ₹1,000,000 each (matching 1B's seed).
- **Invariant (property-tested)**: for any sequence of trades, every `txn_id`'s postings sum to
  zero, and the system-wide sum of all postings is zero (conservation of cash).
- On `TradeExecuted`, ledger posts at the **fill** price (`price × quantity`), publishes one
  `CashPosted` per affected account carrying that account's **signed posting delta** (not an
  absolute balance).

## 5. Risk — cash rewire to `settled − holds`

- **`risk.settled_cash`** `(account, amount)` — a read-model of ledger's settled cash, seeded to
  the openings and **incremented by each `CashPosted`'s signed delta** (idempotent, dedup on event
  id — addition commutes, so out-of-order delivery is safe; an absolute-balance event would not be).
- **`risk.cash_hold`** `(order_id PK, account, unit_price, remaining_qty)` — a reservation placed
  when a BUY is accepted (`unit_price = limit`, `remaining_qty = order qty`); the held amount is
  `unit_price × remaining_qty`.
- **The check**: `available(account) = settled_cash[account] − Σ (unit_price × remaining_qty)` over
  that account's holds. A BUY is approved iff `available ≥ price × qty`, and approval inserts the
  hold in the same synchronous transaction as order acceptance.
- **Release on fill**: `TradeExecuted(buyOrderId, quantity=fillQty)` → `cash_hold[buyOrderId]`'s
  `remaining_qty −= fillQty` (row deleted at zero). Because a buy reserves at its **limit** but
  settles at the **maker** price, releasing `limit × fillQty` of hold while ledger debits only
  `fill × fillQty` refunds the over-reservation `(limit − fill) × fillQty` back into available cash.
- **Sell side unchanged**: holdings checks still use the seeded `available_holdings` — 2B rewires it.
- **Eventual consistency**: the hold release (`TradeExecuted`) and the settled decrement
  (`CashPosted`) arrive as separate events, so `available` is briefly inconsistent between them.
  This is an accepted read-model tradeoff (the reason ADR-0003 chose a projection); reconciliation
  (2C) is the backstop. 2A documents it rather than hiding it.

## 6. Enriched `TradeExecuted` + execution's projection

- `shared.TradeExecuted` gains `String buyerAccount, String sellerAccount`.
- `execution` builds an `orderId → account` projection from the `OrderAccepted` events it already
  consumes (durable table `execution.order_account`, so a resting order's account survives across
  the async gap), and stamps both accounts when it publishes a trade.
- Consumers (`ledger` now; `portfolio` in 2B) read the accounts straight off the event — no module
  rebuilds the mapping.

## 7. Data model & migrations (Flyway, roll-forward)

Migrations are numbered by the **build order** of the task that introduces them (Flyway is
roll-forward only; versions must ascend across tasks): execution-enrich (Task 1) → ledger (Task 2)
→ risk (Task 3).

| Migration | Schema | Tables / changes |
|---|---|---|
| `V5__execution_order_account.sql` | `execution` | `order_account` (order_id → account) projection for trade enrichment |
| `V6__ledger.sql` | `ledger` | `posting` (signed `BIGINT` amounts, `txn_id` grouping); **seeded** opening deposits as balanced postings (`house` + each trader) |
| `V7__risk_cash_rewire.sql` | `risk` | add `settled_cash` (**seeded** to openings) + `cash_hold`; the buy path reads `settled − Σ holds` |

Money and quantities stay `BIGINT` minor units. `JdbcClient` timestamp params use
`OffsetDateTime.now(clock)` (the pgjdbc/`Instant` convention from 1B).

## 8. Testing strategy (TDD)

- **Double-entry property test** (jqwik or example-driven over random trade sequences): every
  transaction balances to zero; total postings sum to zero.
- **`settled − holds` identity**: accept a buy (hold placed), fill it at a maker price below the
  limit, assert available returns to `opening − fill×qty` (the over-reservation refund is exercised).
- **Duplicate-delivery tests (mandated)** for all three new listeners: `ledger`←`TradeExecuted`,
  `risk`←`TradeExecuted`, `risk`←`CashPosted`.
- **Reconciliation-lite IT**: after a fill, `Σ ledger.posting for account == risk.settled_cash[account]`
  (the invariant 2C's job generalizes to `cash + positions ≡ equity`).
- **Module + arch**: `ApplicationModules.verify()` green (`ledger → shared` only; no new cycles);
  JaCoCo ≥ 80%.

## 9. Task breakdown (dependency order)

1. **Enrich `TradeExecuted`** — add accounts to the shared contract; `execution.order_account`
   projection from `OrderAccepted`; stamp on publish (V5). Existing 1B tests updated to the new
   event shape.
2. **`ledger` module** — V6, double-entry `posting` model + opening seed, `TradeExecuted` listener
   (idempotent), `CashPosted` event, balance = `Σ postings`. Double-entry property test.
3. **`risk` cash rewire** — V7, `settled_cash` + `cash_hold`; `risk.check` takes `orderId` and reads
   `settled − Σ holds`; `OrderService` passes the order id. Unit/IT for the check.
4. **`risk` listeners** — `CashPosted` (update settled) + `TradeExecuted` (release hold), both
   idempotent, both with duplicate-delivery tests.
5. **Integration proof** — a fill moves cash end-to-end (buyer debited, seller credited), the
   over-reservation refund lands in available, and `ledger ≡ risk.settled_cash`.
6. **Closeout** — ADRs + gate.

## 10. ADRs to write (Task 6)

- **Double-entry cash ledger** — postings-only (no mutable balance), transactions balance to zero,
  cash is derived; opening deposits as seeded balanced postings.
- **`available = settled − holds`** — the ledger-fed settled read-model + risk-owned holds, the
  over-reservation refund, and the documented eventual-consistency window between the two listeners.

## 11. Definition of done

- A filled trade posts a balanced double-entry cash transaction; buyer settled cash decreases and
  seller's increases by the fill notional — proven by an IT.
- `risk`'s buy check runs off `settled − holds`; the over-reservation refund is exercised by a test.
- All three new listeners have duplicate-delivery tests; `ledger` posts are idempotent.
- `ledger.posting` for an account equals `risk.settled_cash[account]` after a fill (reconciliation-lite).
- `ApplicationModules.verify()` green; JaCoCo ≥ 80%; two ADRs written.
- Full `mvn verify` green.
