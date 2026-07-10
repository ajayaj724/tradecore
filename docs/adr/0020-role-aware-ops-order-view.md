# 0020: Role-aware ops view via a scope parameter on the order list

- Status: accepted
- Date: 2026-07-10

## Context

`ops1`/`admin1` saw the trader screen: no way to observe the whole book, and the ticket
invited submissions the backend would 403 (submit is TRADER-only). The backend already
mapped realm roles to authorities and let OPS read any single order (`findForViewer`).

## Decision

- **Backend:** `GET /api/v1/orders?scope=all` returns every account's orders (newest
  first, same limit clamp), guarded in code by `ROLE_OPS` — a plain `AccessDeniedException`
  → RFC 9457 403 for anyone else. Rejected: a separate `/api/v1/ops/orders` endpoint — one
  resource with an authorization-scoped view keeps the surface smaller and the OpenAPI doc
  honest; and rejected `@PreAuthorize` SpEL on the param, because the in-method check reads
  clearer and tests identically.
- **Web:** Keycloak realm roles ride the access token; `realmRoles()` decodes them
  (unverified, deliberately — the token came from the token endpoint over the back
  channel, and the backend re-validates cryptographically; roles only shape the UI).
  OPS users get a My orders / All accounts toggle; the all-accounts blotter adds the
  Account column and is read-only. The ticket renders only for TRADER.
- **Cancel-on-behalf is deliberately out of scope** — ops cancelling a customer's order
  is a workflow with audit/consent semantics (who requested it, four-eyes), not a UI
  toggle; it needs its own design when it's actually wanted.

## Consequences

- UI role checks are cosmetic by design; every enforcement point stays server-side
  (submit/cancel TRADER-only, scope=all OPS-only, ownership checks on single reads).
- `admin1` (ADMIN) currently has no elevated view — ADMIN semantics remain unassigned,
  matching the rest of the system.
