# ADR-0016: Market orders as capped immediate-or-cancel

- Status: Accepted — the deferred *unpriced* market order is now implemented by
  [ADR-0021](0021-unpriced-market-orders-collared-reference.md) (system-derived collar cap)
- Date: 2026-07-09

## Context

Orders were LIMIT-only. Traders need MARKET orders — "fill now at the best available price." The
obstacle is the same boundary that shaped cancellation (ADR-0015): `risk` runs its reservation check
*before* the engine and may not read the order book. A true no-price market BUY therefore has nothing
to reserve against without feeding a book price into `risk` — new cross-module coupling beyond the one
permitted `orders → risk` call.

## Decisions

**A MARKET order is a marketable-limit, immediate-or-cancel order carrying a protective cap price.**

- `SubmitOrderRequest`/`SubmitOrderCommand` gain an `OrderType {LIMIT, MARKET}`; an omitted `type`
  decodes as `LIMIT`, so existing clients are unaffected. `OrderAccepted` carries the type to the venue.
- For MARKET, `price` is the **protective cap**. Risk reserves `cap × qty` (BUY) or `qty` (SELL) through
  the unchanged `reserveCash`/`reserveHoldings` path — no new coupling, no price fed into risk.
- The engine gains `submitIoc`: it matches available liquidity and **drops** the unfilled remainder
  instead of resting it. This is atomic under the engine lock, so the remainder is never briefly visible
  to a concurrent order (a submit-then-cancel in the venue would leave that window).
- After an IOC match the venue publishes `OrderCancelled(remainder)` for any unfilled quantity, reusing
  the cancellation machinery from ADR-0015: `risk` frees the unused cap hold and `orders` marks the
  order `CANCELLED`.

**`CANCELLED` is now terminal against a late fill.** A partial market fill emits both a `TradeExecuted`
(the fill) and an `OrderCancelled` (the remainder); their async delivery order is nondeterministic.
`applyTrade` now books a fill onto an already-`CANCELLED` order without resurrecting its status, so the
terminal label is deterministic. This also resolves the edge case ADR-0015 deferred.

## Consequences

- MARKET is fully event-driven and adds no synchronous coupling: `ApplicationModules.verify()` still
  passes. Validation keeps `price` positive (it is the cap), so no request shape becomes ambiguous.
- The engine stays Spring-free; a jqwik property pins the core guarantee — an IOC order never leaves
  resting quantity in the book, whatever the book state. Venue ITs pin the crossing/partial behavior
  deterministically; the end-to-end IT covers the race-free no-liquidity path (MARKET → CANCELLED, cap
  hold released).
- **Deferred:** a MARKET order still requires a cap price. A true unpriced market order would need a
  book price surfaced to risk (an event round trip or a new dependency) and is left for a future ADR if
  demanded. Slippage beyond the cap is by design not possible — the cap bounds the worst fill price.
