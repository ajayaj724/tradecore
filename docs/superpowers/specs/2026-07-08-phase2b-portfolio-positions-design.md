# tradecore Phase 2B — Portfolio, Prices & P&L: Design Spec

- **Date:** 2026-07-08
- **Status:** Approved (brainstorming) — pending implementation plan
- **Extends:** [`2026-07-06-brokerage-oms-design.md`](2026-07-06-brokerage-oms-design.md) §10 Phase 2 (slice 2B of 3)
- **Builds on:** Phase 2A (ledger & cash) — reuses the `available = settled − holds` pattern
- **Settles the debt from:** [ADR-0003](../../adr/0003-risk-seeded-read-model-projection.md) — the **holdings** half of the risk rewire
- **Branch:** `feat/phase2b-portfolio-positions`

## 1. Purpose & scope

Slice 2B is the positions-and-P&L half of Phase 2, structurally symmetric to 2A: `portfolio`
becomes the source of truth for share positions (as `ledger` is for cash), `marketdata` supplies
last prices, and risk's `available_holdings` rewires from a seeded number to
`settled_holdings(portfolio-fed) − holds`.

### 1.1 In scope

- New **`marketdata`** module: `PriceUpdated` events; last prices seeded (openings) and updated from
  each trade's fill price.
- New **`portfolio`** module: per-(account, symbol) position as `total_qty` + `total_cost`, realized
  P&L on sales, unrealized P&L marked from `PriceUpdated`. Publishes `HoldingsPosted` (share delta).
- Rewire risk's **holdings** side to `available_holdings = settled_holdings(portfolio-fed) − Σ holds`,
  mirroring 2A's cash rewire; extend the existing `TradeExecuted` listener to release the seller's
  share hold.

### 1.2 Out of scope

- **Reconciliation** (`cash + Σ position×price ≡ account equity`) and **full Grafana dashboards** —
  slice 2C.
- **Corporate actions, dividends, short positions, multi-currency** — v1-out (spec §9).
- **External market-data feed** — prices are seeded + trade-derived; no live feed.

## 2. Decisions locked in brainstorming

| # | Fork | Decision | Rationale |
|---|---|---|---|
| 1 | Cost basis | **Store `total_cost` + `total_qty`; derive average** | Keeps positions exact with integer math; the only rounding is the cost of shares sold (`total_cost×soldQty/total_qty`), with the residual carried in the remaining `total_cost` (self-correcting). No stored/compounding average |
| 2 | Price source | **Seed openings + derive from trades** | Self-contained (no external feed); a seed means unrealized P&L exists pre-trade, and `last_price = fill price` keeps it live. `marketdata` consumes `TradeExecuted` and publishes `PriceUpdated` |
| 3 | Holdings model | **Mirror 2A: `settled − holds`, portfolio-fed** | Applies the ADR-0007 pattern to shares; not a new fork |

## 3. Structure & flow

```
TradeExecuted (buyer/seller accounts, symbol, fill price, qty)
  ├─ portfolio consumes (idempotent)
  │     BUYER  position: total_qty += qty ; total_cost += price×qty
  │     SELLER position: cost = total_cost×qty/total_qty (integer div) ;
  │                      realized_pnl += price×qty − cost ; total_cost −= cost ; total_qty −= qty
  │     → publishes HoldingsPosted(account, symbol, +qty) [buyer], HoldingsPosted(account, symbol, −qty) [seller]
  │           └─ risk consumes HoldingsPosted → settled_holdings[account,symbol] += delta
  ├─ marketdata consumes (idempotent) → last_price[symbol] = fill price → publishes PriceUpdated(symbol, price)
  │           └─ portfolio consumes PriceUpdated → marks positions (unrealized = last_price×qty − total_cost, EXACT)
  └─ risk consumes (existing 2A listener, extended) → release SELLER's holdings hold (sellOrderId) by qty

SELL accept: risk.check(SELL) → available_holdings = settled_holdings[account,symbol] − Σ holds ≥ qty ? insert holdings hold
```

- `portfolio`, `marketdata` depend on `shared` only. `risk` gains a `HoldingsPosted` listener and
  extends its `TradeExecuted` handler; no new module-to-module edges (all fan into `shared`).
- Holdings holds need **no over-reservation refund** (shares are price-independent), so the sell-side
  release is exact — simpler than the cash side.

## 4. Portfolio — positions & P&L (integer)

- **`portfolio.position`** `(account, symbol, total_qty, total_cost, realized_pnl)`, PK `(account,
  symbol)`. All `BIGINT`.
- **BUY fill:** `total_qty += qty`, `total_cost += price×qty` (both exact).
- **SELL fill:** `cost = total_cost × qty / total_qty` (integer division); `realized_pnl += price×qty
  − cost`; `total_cost −= cost`; `total_qty −= qty`. The rounding residual stays in `total_cost`.
- **Unrealized P&L** (derived on read, exact): `last_price × total_qty − total_cost`.
- **Average cost** (derived, display only): `total_cost / total_qty`.
- Idempotent consumers (dedup on event id). A buyer with no prior position starts at zero.

## 5. Marketdata — prices

- **`marketdata.last_price`** `(symbol, price)`, seeded with opening prices for the demo instruments.
- Consumes `TradeExecuted` (idempotent) → `last_price[symbol] = fill price` → publishes
  `PriceUpdated(symbol, price)`.

## 6. Risk — holdings rewire (mirror of 2A cash)

- Drop the seeded `available_holdings`; add **`risk.settled_holdings`** `(account, symbol, qty)`
  (seeded to openings, fed by `HoldingsPosted`) and **`risk.holdings_hold`** `(order_id, account,
  symbol, remaining_qty)`.
- **SELL check:** `available = settled_holdings[account,symbol] − Σ holdings_hold ≥ qty` → approve +
  insert hold (keyed by `orderId`), under `SELECT … FOR UPDATE` on the settled row.
- **Release on fill:** extend the existing `TradeExecuted` listener to also decrement the seller's
  `holdings_hold[sellOrderId]` by the filled qty (deleted at zero) — alongside the 2A buyer cash-hold
  release.
- **`HoldingsPosted` listener:** `settled_holdings += delta` (idempotent, signed-delta, commutative).

## 7. Events (in `shared`)

- `record PriceUpdated(UUID eventId, String symbol, long price, Instant occurredAt)`
- `record HoldingsPosted(UUID eventId, String account, String symbol, long qty, Instant occurredAt)`
  (`qty` = signed share delta)

## 8. Data model & migrations (Flyway, roll-forward; ascending by build order)

| Migration | Schema | Tables / changes |
|---|---|---|
| `V8__marketdata.sql` | `marketdata` | `last_price` (**seeded** openings) + `processed_event` |
| `V9__portfolio.sql` | `portfolio` | `position` (total_qty, total_cost, realized_pnl) + `processed_event` |
| `V10__risk_holdings_rewire.sql` | `risk` | drop `available_holdings`; add `settled_holdings` (**seeded**) + `holdings_hold` |

Money/quantities stay `BIGINT`. `JdbcClient` timestamp params use `OffsetDateTime.now(clock)`.

## 9. Testing strategy (TDD)

- **Portfolio math**: buy accumulates `total_qty`/`total_cost`; a sell realizes `price×qty − cost`
  with integer cost-of-sold; unrealized = `last_price×qty − total_cost`. Include a **round-trip
  conservation** check (buy then fully sell ⇒ `total_qty = 0`, `total_cost = 0`, realized P&L =
  proceeds − original cost).
- **Marketdata**: a trade updates `last_price` and emits `PriceUpdated`.
- **Risk holdings**: `available = settled − holds`; `rejectsSellWhenHoldingsInsufficient` under the new
  model; the extended `TradeExecuted` listener releases the seller hold.
- **Duplicate-delivery tests (mandated)** for every new/extended listener: `portfolio`←`TradeExecuted`,
  `portfolio`←`PriceUpdated`, `marketdata`←`TradeExecuted`, `risk`←`HoldingsPosted`.
- **End-to-end**: a buy builds a position + cost basis; a subsequent trade marks it (unrealized P&L);
  a sell realizes P&L and settles holdings (`portfolio` position == `risk.settled_holdings`).
- `ApplicationModules.verify()` green; JaCoCo ≥ 80%.

## 10. Task breakdown (dependency order)

1. **`shared` events + `marketdata`** — `PriceUpdated`, `HoldingsPosted`; `marketdata` seed +
   trade-derived prices (V8).
2. **`portfolio` module** — position math, realized/unrealized P&L, `TradeExecuted` + `PriceUpdated`
   listeners, `HoldingsPosted` publish (V9).
3. **`risk` holdings rewire** — `settled_holdings` + `holdings_hold` (V10); `check` SELL reads
   `settled − holds`; `HoldingsPosted` listener; extend `releaseHold` for the seller side.
4. **End-to-end proof** — position, mark, realized P&L, holdings settle; reconciliation-lite
   (`portfolio.total_qty == risk.settled_holdings`).
5. **Closeout** — ADRs + gate.

## 11. ADRs to write (Task 5)

- **Integer average-cost & P&L** — `total_cost + total_qty` (no stored average), integer
  cost-of-sold with residual carried, division-free unrealized P&L.
- (Holdings rewire reuses ADR-0007; note the symmetry rather than duplicating it.)

## 12. Definition of done

- A buy builds a position (`total_qty`, `total_cost`); a sell realizes integer P&L and reduces the
  position — proven by an IT with the round-trip conservation check.
- `marketdata` publishes `PriceUpdated` from trades; `portfolio` marks unrealized P&L exactly.
- Risk's sell check runs off `settled_holdings − holds`; the seller's hold releases on fill.
- Every new/extended listener has a duplicate-delivery test; `portfolio.total_qty ==
  risk.settled_holdings` after a fill (reconciliation-lite).
- `ApplicationModules.verify()` green; JaCoCo ≥ 80%; ADR written; full `mvn verify` green.
