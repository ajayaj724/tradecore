# 0002: `/actuator/prometheus` exposure for local compose scraping

- Status: accepted (local-only) — production posture **resolved by [ADR-0017](0017-prometheus-scrape-secure-by-default.md)** (secure by default, local-profile opt-in)
- Date: 2026-07-06

## Context

Task 7 locked down all endpoints except `/actuator/health` behind OIDC bearer-token auth.
Task 8 adds a Prometheus container to the local compose stack that scrapes
`/actuator/prometheus` on the host app. Prometheus's `static_configs` scraper cannot
attach an OAuth2 bearer token per request the way an authenticated client can, so an
unauthenticated `/actuator/prometheus` is the pragmatic choice for the local stack.

## Decision

`SecurityConfig` permits `/actuator/prometheus` unauthenticated, with an in-code comment
(`// local-only compose scrape; lock down before any non-local deploy`) marking it as a
local-development concession, not a production posture. No other matcher is widened.

Production hardening is **not implemented in this task** and is deferred to one of:

- **mTLS**: Prometheus and the app share a CA; the app's filter chain requires a client
  certificate on `/actuator/prometheus` (e.g. via a dedicated connector/port or a reverse
  proxy that terminates mTLS before the app).
- **Static bearer token**: Prometheus's `scrape_configs` sends a fixed `Authorization:
  Bearer <token>` header (`authorization: { credentials_file: ... }`); the app validates
  it via a separate, narrowly-scoped filter (not the OIDC resource-server chain, since
  Prometheus cannot do the OAuth2 client-credentials dance per scrape easily without
  Prometheus's newer OAuth2 scrape config, which is itself a viable alternative).

## Consequences

- Any non-local deployment (staging, prod) must close this matcher or bind it to one of
  the two options above before `/actuator/prometheus` is reachable outside the compose
  network. The loopback-only port bindings (`127.0.0.1:9090`, etc., per the prior
  loopback-binding decision) limit exposure today, but the Spring Security matcher itself
  is not network-scoped and must not be trusted as the sole control past local dev.
- Follow-up work (tracked outside this ADR) must pick one of the two production options
  before any non-local compose/deploy target is stood up.
