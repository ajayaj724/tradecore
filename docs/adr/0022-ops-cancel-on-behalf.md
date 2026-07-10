# 0022: Ops cancel-on-behalf with audited principal

- Status: accepted
- Date: 2026-07-10
- Supersedes the exclusion in [ADR-0020](0020-role-aware-ops-order-view.md)

## Context

ADR-0020 excluded ops cancellation pending an answer to accountability ("who requested
it"). The answer was already in the code: `OrderService.cancel` has always separated the
*account* (owner) from the *principal* (actor), and every cancel writes a
`CANCEL_REQUESTED` audit row naming the principal. Only the authorization gate was missing.

## Decision

- `POST /api/v1/orders/{id}/cancel` now accepts **TRADER or OPS**. TRADER keeps the
  ownership rule (non-owner → 404, existence not leaked); **OPS bypasses ownership only** —
  order-state rules (working orders only → 409 otherwise) apply identically. Same
  authorization-scoped-single-endpoint pattern as `scope=all` (ADR-0020).
- Accountability is the **audit trail**: the `CANCEL_REQUESTED` row records the ops
  principal, while the order and its events keep the owner's account — pinned by IT.
- The ops blotter enables Cancel with an explicit confirm naming the target account —
  cancelling someone else's order must be deliberate, never a misclick.
- **Maker-checker (four-eyes) is still out of scope**: a second-approver workflow needs a
  pending-approval state on the order lifecycle, which is a product decision about the
  order state machine, not an authorization toggle. This ADR covers single-actor ops
  cancellation with full attribution.

## Consequences

- Ops can act on a stuck or fat-fingered order without impersonating the customer, and
  the audit answers "who did this" for every cancellation, self-service or on-behalf.
- The risk hold release and cash effects are unchanged — the cancel path downstream of
  authorization is byte-for-byte the same event choreography as ADR-0015.
