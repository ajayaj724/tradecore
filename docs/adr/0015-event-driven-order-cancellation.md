# ADR-0015: Event-driven order cancellation

- Status: Accepted
- Date: 2026-07-09

## Context

Orders were submit-only. Traders need to cancel a working order (`ACCEPTED` or `PARTIALLY_FILLED`)
and have the unfilled cash/holdings reservation returned. The obstacle is a module-boundary
invariant: the only permitted synchronous cross-module call is `orders → risk`. `orders` may **not**
call `execution` or the matching engine directly, so removing an order from the book cannot be a
synchronous method call. Cancellation therefore has to be modelled as a published-event conversation.

## Decisions

**Cancel is a two-event round trip, not a synchronous command.**

1. `POST /api/v1/orders/{id}/cancel` (TRADER-only, owner-scoped) → `OrderService.cancel`. It validates
   ownership (a non-owner gets **404**, never leaking existence) and status (a terminal order gets
   **409** `OrderNotCancellableException`, rendered as an RFC 9457 Problem Detail). On success it
   publishes **`OrderCancelRequested`** and returns **202 Accepted** — the order is *not* set to
   `CANCELLED` synchronously; the terminal state comes back via event.
2. `execution` consumes `OrderCancelRequested`, calls the framework-free engine's new
   `MatchingEngine.cancel(symbol, orderId)` (removes the resting remainder, returns the quantity that
   was still open), and publishes **`OrderCancelled(cancelledQty)`**.
3. `orders` consumes `OrderCancelled` → marks the order `CANCELLED` (keeping `filledQty`).
4. `risk` consumes `OrderCancelled` → releases the hold.

**Risk releases by deleting the hold keyed by `order_id`.** The hold's `remaining_qty` already tracks
only the *unfilled* reservation (each fill decrements it), so `delete from cash_hold/holdings_hold
where order_id = :o` returns exactly the unused amount — no need to thread `cancelledQty` into risk.

**The cancel-before-accept race is closed with a marker table** (`execution.cancelled_order`, migration
`V11`). Because `OrderAccepted` is delivered asynchronously, a cancel can reach `execution` before the
accept has rested the order. The cancel handler writes the marker first; `submit(OrderAccepted)` checks
it and refuses to rest an order already marked cancelled. `engine.cancel` then harmlessly returns 0.

**Idempotency.** Every new listener ships a duplicate-delivery test. `execution` and `risk` reuse their
existing `processed_event` guard keyed by the event id; `orders.applyCancel` guards on status (a
non-working order is left untouched), which also lets a fill that won the race keep its `FILLED` status.

## Consequences

- No new synchronous cross-module coupling: `ApplicationModules.verify()` still passes. The cancel path
  is entirely event-driven, matching the existing fill/settlement pattern.
- The matching engine gains one pure, `synchronized` method (`cancel`), covered by jqwik properties
  (cancel frees exactly the resting remainder, leaves the rest of the book untouched, and an unknown id
  is a 0-return no-op). The engine stays Spring-free.
- **Known limitation (deferred):** for a `PARTIALLY_FILLED` order, a `TradeExecuted` that was generated
  before the cancel but delivered *after* `OrderCancelled` would re-advance the status via `applyTrade`.
  Money stays correct (settlement is ledger-driven and risk releases at most once); only the terminal
  status label could flip. The scoped happy path (a lone resting order) cannot hit this. Hardening
  `applyTrade` to treat `CANCELLED` as terminal is left as a follow-up.
- Market orders (a future ADR) will reuse this machinery: an IOC order matches, then cancels its own
  unfilled remainder rather than resting it, freeing the unused hold through the same release path.
