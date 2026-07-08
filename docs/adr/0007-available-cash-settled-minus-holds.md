# 0007: Available cash = settled (ledger-fed) − reservation holds

- Status: accepted
- Date: 2026-07-08

## Context

In Phase 1B, `risk.available_cash` was a single seeded number decremented on order accept;
[ADR-0003](0003-risk-seeded-read-model-projection.md) deferred rewiring it to be event-fed. Phase
2A makes `ledger` the owner of settled cash, so risk must stop owning the cash truth and start
projecting it — while still gating pre-trade on funds that are committed to open orders.

## Decision

`available(account) = settled_cash[account] − Σ (unit_price × remaining_qty)` over the account's
open `cash_hold`s.

- **Settled** is a read-model fed by the ledger's signed-delta `CashPosted` events
  (`settled += amount`, deduped on event id — addition commutes, so out-of-order delivery is safe;
  an absolute-balance event would not be).
- **Holds** are risk-owned: a BUY inserts a hold (`unit_price = limit`, `remaining_qty = order
  qty`) in the same synchronous transaction as acceptance; a fill (`TradeExecuted`) decrements the
  hold's `remaining_qty` by the filled amount (row deleted at zero).
- Because a buy reserves at its **limit** but settles at the **maker** price, releasing
  `limit × fillQty` of hold while the ledger debits only `fill × fillQty` refunds the
  over-reservation `(limit − fill) × fillQty` back into available cash the instant the trade prints.

## Consequences

- ADR-0003 is fulfilled for cash: risk projects settled cash from ledger events and never reads
  another module's tables; `orders → risk` stays the only synchronous edge.
- The over-reservation refund is automatic and correct, which is why a single `available_cash`
  number could not work — available and settled are genuinely different quantities.
- The hold-release (`TradeExecuted`) and settled-update (`CashPosted`) arrive as **two events**, so
  `available` is briefly inconsistent between them (an accepted read-model tradeoff). Reconciliation
  (2C) is the backstop. A fatter single `BuyerSettled` event carrying orderId + qty was rejected as
  event-coupling.
- The **holdings** side of risk is still seeded (decrement-on-accept); it is rewired to
  `portfolio` events in slice 2B.
