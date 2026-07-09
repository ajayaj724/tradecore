# 0017: Secure `/actuator/prometheus` by default; expose only under the local profile

- Status: Accepted (resolves the deferral in [ADR-0002](0002-actuator-prometheus-scrape-exposure.md))
- Date: 2026-07-10

## Context

[ADR-0002](0002-actuator-prometheus-scrape-exposure.md) permitted `/actuator/prometheus`
unauthenticated for the local compose Prometheus scraper and explicitly deferred the production
posture, leaving an in-code `// lock down before any non-local deploy` marker. That marker was the
one outstanding pre-deploy security item: a non-local deployment inherited an unauthenticated
metrics endpoint unless someone remembered to close it.

## Decision

**The scrape endpoint is authenticated by default and opened only under the `local` profile.**

- `SecurityConfig` gates the `permitAll` matcher for `/actuator/prometheus` on a boolean property
  `tradecore.security.expose-prometheus-scrape`, which **defaults to `false`**. When false the
  endpoint falls through to `anyRequest().authenticated()` — a 401 RFC 9457 Problem Detail for an
  unauthenticated caller, like every other endpoint.
- `src/main/resources/application-local.yaml` sets the property `true`. `scripts/run.sh` activates
  the profile (`-Dspring-boot.run.profiles=local`), so the host-run app that the compose Prometheus
  container scrapes still exposes metrics locally.
- Two integration tests pin both sides: `SecurityConfigIT.prometheusRequiresAuthByDefault` (401 with
  no profile) and `SecurityConfigPrometheusIT` under `@ActiveProfiles("local")` (200).

## Consequences

- **Non-local deployments are secure by default** — there is nothing to remember to "lock down"; the
  ADR-0002 marker is removed. Enabling the `local` profile in a non-local environment would re-open
  the endpoint, so that profile must stay local (its whole purpose is developer-machine convenience).
- If a real deployment needs remote Prometheus scraping, use one of ADR-0002's transport options
  (mTLS or a static bearer token via a narrowly-scoped filter) rather than the `local` profile.
- The gate is a single boolean property, so a deployment could also open the endpoint deliberately
  (e.g. behind a trusted network boundary) by setting the property — an explicit, auditable choice
  rather than a silent default.
