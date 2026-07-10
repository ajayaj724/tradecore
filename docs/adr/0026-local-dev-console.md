# 0026: Local dev console served by the app (with a demo-token minter)

- Status: accepted (local-only)
- Date: 2026-07-10

## Context

`console.html` is a dev launcher — service links, command cheat-sheet, endpoint/credential
tables. It could only *copy* commands; the action buttons couldn't call the API because the
backend has no CORS (by design — the reason the web BFF exists), and getting a token in the
browser means a cross-origin call to Keycloak (also CORS-gated). So the console was read-only.

## Decision

Serve the console **from the backend itself** so its action buttons are same-origin with the
API — no CORS, and no secret in the page:

- `LocalConsoleController` (`@Profile("local")`) serves `GET /console.html` (from
  `resources/local-console/`) and `GET /local/token/{user}`, which performs the demo password
  grant **server-side** and returns the access token. Same-origin token acquisition sidesteps
  Keycloak CORS entirely.
- `SecurityConfig` permits `/console.html` and `/local/**` only when
  `tradecore.security.expose-local-console` is true — set solely in `application-local.yaml`,
  the same flag-gated concession pattern as the prometheus scrape ([ADR-0017](0017-prometheus-scrape-secure-by-default.md)).
- **Two independent gates**: the controller is `@Profile("local")` (absent off-local) *and*
  the permit is flag-gated. Off-local, `/local/token/{user}` neither exists nor is permitted —
  it can never become a token-minting hole in a deployed instance.
- The buttons call `/api/v1/*` same-origin with the minted bearer (and an `Idempotency-Key` on
  POST, like every other client). Errors surface the backend's problem-detail.

Rejected: a browser-side password grant + a local CORS filter — two cross-origin surfaces
(backend + Keycloak) to open instead of zero. Same-origin serving is strictly smaller.

## Manage the application (control server)

A browser page cannot run `scripts/*.sh`, so the **Manage** buttons need a tiny local process:
`scripts/console.sh` (→ `scripts/console-server.mjs`, bun/node, no deps) binds `127.0.0.1:8090`,
serves the console, **proxies** `/api`+`/local`+`/actuator` to the backend (so Operations stay
same-origin), and runs a **fixed allowlist** of scripts by key on `POST /manage/{key}`
(platform up/down/wipe, backend start/stop, UI start/stop, gate, smoke). No request data ever
reaches a shell — only pre-declared commands run. Access it at `http://localhost:8090/console.html`;
served from the backend instead, the Manage buttons degrade to a hint (the copy-commands still work).

## Consequences

- The console is a genuine local control panel (place/cancel orders, read positions/balances
  as any demo user) — no terminal needed. It is inert and inaccessible outside local dev.
- One more thing to remember to keep local-only; the double gate (profile + flag) makes that
  a structural guarantee, not a discipline.
