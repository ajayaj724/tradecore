# 0024: Maker-checker (four-eyes) ops cancellation

- Status: accepted
- Date: 2026-07-10
- Supersedes the single-actor ops cancel of [ADR-0022](0022-ops-cancel-on-behalf.md)

## Context

ADR-0022 let one ops user cancel any order with audit attribution, and named maker-checker
as the open product decision. That decision is now made: acting on a customer's order
requires a second pair of eyes.

## Decision

- An **ops** `POST /orders/{id}/cancel` no longer cancels: it validates the order is
  working and parks a `PENDING` row in `orders.cancel_request` (V14; a partial unique
  index allows one live request per order — insert-first duplicate protection). The order
  itself is untouched: no event is published until a decision.
- **A different ops user decides** via `POST /cancel-requests/{id}/approve` (executes the
  ADR-0015 cancel choreography) or `/decline`. Self-approval is refused (403); declining
  your own request is allowed — that's withdrawal, not control collapse. ADMIN may list
  requests but not decide (ADR-0023's observe-only stance).
- Rejected: a `CANCEL_PENDING` order status — approval state is metadata about an
  *intent*, not a lifecycle stage of the order; keeping it in a side table leaves the
  order state machine and every consumer of it untouched.
- **Trader self-cancels stay immediate** — dual control governs acting on someone else's
  property, not on your own.
- Audit per decision: `CANCEL_APPROVAL_REQUESTED` (requester) → `CANCEL_APPROVED`/
  `CANCEL_DECLINED` (decider), then the usual cancel trail. `ops2` joins the demo realm so
  four-eyes is demonstrable.

## Consequences

- A lone compromised or careless ops account can no longer remove customer orders.
- An approved request can still hit a just-filled order: the cancel then 409s and the
  request stays decided — the fill won, which is the correct market outcome.
