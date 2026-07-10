# 0021: Unpriced market orders via a collared reference price

- Status: accepted
- Date: 2026-07-10
- Resolves the deferral in [ADR-0016](0016-market-orders-as-capped-ioc.md)

## Context

ADR-0016 shipped MARKET as a capped IOC but required the *client* to supply the protective
cap, because risk may not read the order book and had no price to reserve against. A true
market order — no price at all — needed a reference price inside risk.

## Decision

**Risk keeps its own reference-price projection, fed by marketdata's `PriceUpdated` events**
(`risk.reference_price`, seeded to marketdata's V8 openings). This is the "event round trip"
ADR-0016 anticipated: still no synchronous coupling, `ApplicationModules.verify()` unchanged.

- The projection is **last-write-wins by event time**: a conditional upsert guarded on
  `updated_at <= excluded.updated_at` makes duplicate delivery a no-op and keeps a stale
  redelivery from regressing a newer price — idempotency without a processed-event table,
  because the operation is naturally convergent.
- An unpriced order (`price` omitted; valid only for MARKET, enforced by bean validation)
  gets an **effective price = reference ± 5% collar** (basis-point integer math on paise):
  cap for BUY, floor for SELL. Risk reserves at it, and it becomes the engine's protective
  cap — the engine contract (capped IOC, ADR-0016) is untouched. No reference price →
  rejected `"no reference price"`, same shape as any risk rejection.
- `RiskDecision.Approved` now carries `effectiveUnitPrice`; orders persists it on
  acceptance, so the response and blotter show the actual protective bound. Rejected:
  returning the engine cap out-of-band or having orders query marketdata directly — the
  first splits one decision across two calls, the second adds a new module dependency when
  risk already owns the reservation decision.
- **Staleness (enforced since 2026-07-10):** an unpriced order is rejected
  `"stale reference price"` when the reference is older than
  `tradecore.risk.reference-max-age` (default `PT1H`; the local profile uses `PT24H` so a
  quiet demo book stays usable). The collar bounds a moving market; the window bounds a
  dead feed.

## Consequences

- The UI's MARKET ticket sends no price; the 5% collar is disclosed inline. LIMIT behavior
  and priced-cap MARKET (API-level) are unchanged — `price` remains an optional cap.
- Slippage stays bounded exactly as in ADR-0016; only the cap's author changed
  (system-derived instead of client-supplied).
- The risk module gains one listener; its duplicate-delivery and ordering guarantees are
  pinned by `ReferencePriceProjectionIT`.
