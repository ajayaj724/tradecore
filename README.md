# tradecore

An enterprise brokerage order-management system, built as a modular monolith on Spring Boot 4
and Spring Modulith. This repository is the **Phase 1A chassis**: the platform, security,
observability, and quality-gate scaffolding that every domain module (orders, risk, execution,
matching engine, ...) will be built on in the phases that follow. Full design rationale lives
in [`docs/superpowers/specs/2026-07-06-brokerage-oms-design.md`](docs/superpowers/specs/2026-07-06-brokerage-oms-design.md).

> **Status:** Phase 1A complete. No domain/trading functionality exists yet — see
> [What's not here yet](#whats-not-here-yet) before opening an issue about a missing feature.

## Demo credentials — local only

The compose stack ships a pre-imported Keycloak realm with four throwaway users. These
exist **only** in the local `docker compose` stack, are never used outside it, and are
intentionally allowlisted in [`.gitleaks.toml`](.gitleaks.toml) as non-secrets.

| User | Password | Role |
|---|---|---|
| `trader1` | `demo` | `TRADER` |
| `trader2` | `demo` | `TRADER` |
| `ops1` | `demo` | `OPS` |
| `admin1` | `demo` | `ADMIN` |

Keycloak admin console: `admin` / `admin` at http://localhost:8081. Database: `tradecore` /
`tradecore` at `localhost:5432`. **Do not reuse these anywhere else, and never point this
compose stack at a non-local network.**

## Quick start

Requires Docker, JDK 25, and Maven (or use the wrapper — none is currently checked in).

```bash
scripts/up.sh    # starts Postgres, Keycloak (realm pre-imported), OTel Collector, Tempo, Loki, Prometheus, Grafana
scripts/run.sh   # runs the app in the foreground (auto-starts the platform if it isn't up)
```

In another terminal, once you see `Started TradecoreApplication`:

```bash
# health check — public, no auth
curl -s http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}

# unauthenticated request to a protected path — RFC 9457 problem+json, 401
curl -si http://localhost:8080/api/v1/orders/1
# HTTP/1.1 401
# Content-Type: application/problem+json;charset=UTF-8
# {"detail":"Authentication required","instance":"/api/v1/orders/1","status":401,"title":"Unauthorized"}

# one order fills end-to-end: trader2 rests a sell, trader1 crosses it with a buy
scripts/api.sh POST /api/v1/orders '{"symbol":"ACME","side":"SELL","price":10000,"quantity":5}' trader2
scripts/api.sh POST /api/v1/orders '{"symbol":"ACME","side":"BUY","price":10000,"quantity":5}'  trader1
# each returns 201 with the order in status ACCEPTED and an "id"

# read the buy back by its id — status FILLED, filledQty 5, matched through the embedded engine
scripts/api.sh GET /api/v1/orders/<buyId> trader1
# {"id":<buyId>,"symbol":"ACME","side":"BUY","price":10000,"quantity":5,"filledQty":5,"status":"FILLED"}
```

Prices and quantities are **minor units** (paise / whole shares) end-to-end — no floating
point touches money. A pre-trade risk rejection is a `201` with `status: REJECTED` (a domain
outcome, not a fault); unknown symbols and other faults are RFC 9457 Problem Details. One
OpenTelemetry trace spans the whole path — `POST` → risk → `OrderAccepted` → engine →
`TradeExecuted` → the order reaching FILLED — viewable in Grafana at http://localhost:3000.

Other scripts: `scripts/down.sh [--wipe]` stops the platform (`--wipe` also drops volumes),
`scripts/api.sh METHOD PATH [json] [user]` makes an authenticated call with an
auto-generated `Idempotency-Key` (POST) and `X-Correlation-Id`, `scripts/gate.sh` runs the
full local quality gate, `scripts/logs.sh [service]` tails compose logs, `scripts/psql.sh`
opens a database shell.

## Architecture

Single deployable, Spring Modulith-verified. Phase 1B added the first domain modules —
`orders`, `risk`, `execution` (with a framework-free matching engine), and a `shared`
contracts module — alongside the `config` package for security and error handling.
`ApplicationModules.verify()` runs as a test (`ModularityTests`) on every build, so boundary
violations fail the gate rather than getting caught in review.

The same test class generates Modulith's architecture documentation (C4 component diagram,
module dependency diagram) from the live code:

```bash
mvn test -Dtest=ModularityTests
# output: target/spring-modulith-docs/ (components.puml, module-config.puml, all-docs.adoc)
```

These are build output, not committed — regenerate them locally, or render the `.puml`
files with any PlantUML viewer. The target module map (`orders`, `risk`, `execution`,
`marketdata`, `portfolio`, `ledger`, `reconciliation`) and the event-driven integration
pattern between them are documented in the [design spec, §3](docs/superpowers/specs/2026-07-06-brokerage-oms-design.md#3-architecture)
— all of those modules exist in this repository now, with `reconciliation` (Phase 2C) a
read-only fan-in reporting module that depends on the others but is depended on by none.

Architecture decisions are recorded as ADRs in [`docs/adr/`](docs/adr/):

- [0001 — transactional outbox and redelivery](docs/adr/0001-transactional-outbox-and-redelivery.md):
  cross-module events go through the Spring Modulith Event Publication Registry, a
  Flyway-owned Postgres outbox, proven by an integration test and now exercised by the
  `orders`, `risk`, `execution`, and `portfolio` publishing modules.
- [0002 — `/actuator/prometheus` scrape exposure](docs/adr/0002-actuator-prometheus-scrape-exposure.md):
  why that one endpoint is unauthenticated locally, and what closes it before any non-local
  deploy.

## Security

OAuth2 resource server validating Keycloak-issued JWTs. Roles `TRADER`, `OPS`, `ADMIN` are
mapped from the JWT's `realm_access.roles` claim to Spring Security authorities. Every
endpoint requires authentication except `/actuator/health` and (locally only, see ADR-0002)
`/actuator/prometheus`. Authentication and authorization failures render as RFC 9457
`application/problem+json`, not framework default HTML/JSON — see
`ProblemDetailsAuthHandlers` and `GlobalExceptionHandler`.

## Observability

The app emits OTLP traces and Prometheus-scraped metrics only; the compose stack wires them
to a full local stack:

- Traces: OTel Collector → Tempo (query at http://localhost:3200)
- Metrics: Prometheus scrapes `/actuator/prometheus` (http://localhost:9090)
- Correlation ids: every request gets an `X-Correlation-Id` — accepted if the caller sends
  one, generated otherwise — echoed on the response and available in every log line for
  that request via MDC (`CorrelationIdFilter`)
- Grafana at http://localhost:3000 (anonymous viewer access, local only), with Tempo/Loki/Prometheus
  provisioned as data sources

Grafana (`:3000`, anonymous viewer) auto-provisions the **tradecore — OMS overview** board from
`infra/grafana/provisioning/dashboards/`: order throughput, fill-latency p50/p99, risk-rejection
rate, event-registry lag, reconciliation drift (`tradecore_reconciliation_drift_pairs`, flat at 0
when consistent), JVM/virtual-thread health, and (Phase 3A) the Upstox circuit-breaker state and
market-data feed staleness in seconds. **If Grafana was already running, restart it** —
dashboard provider configs load at startup, so a live container won't pick up the two new panels
until it restarts (`docker compose restart grafana`, or `scripts/down.sh && scripts/up.sh`).

### Upstox market-data adapter (Phase 3A)

`marketdata` polls Upstox for last-traded price on a fixed schedule via a plain `RestClient`
(read-timeout bounded, no `TimeLimiter` — see
[ADR-0010](docs/adr/0010-resilient-external-market-data-adapter.md) for why), guarded by a
Resilience4j retry + circuit breaker (`upstox` instance). On a breaker-open or exhausted-retry
call, the feed **retains the last known good price** rather than propagating a fault or a stale
zero — `UpstoxFeedMetrics` publishes `tradecore.marketdata.feed.staleness.seconds`
(`tradecore_marketdata_feed_staleness_seconds` scraped) so a stuck feed is visible even while the
breaker stays quiet. The feed is stub-driven today (WireMock in tests, a local stub in dev); point
`tradecore.upstox.base-url` and `tradecore.upstox.access-token` at the real Upstox sandbox and no
code changes are needed to go live.

## Quality gate

`mvn verify` runs the full local machine gate — the same one CI runs, so a green `mvn verify`
means a green CI build:

- **Format** — Spotless (Palantir Java Format); `mvn spotless:apply` to fix, `spotless:check` to verify
- **Static analysis** — Error Prone + NullAway (JSpecify null-safety, matching Spring
  Framework 7's own annotations)
- **Structural rules** — Checkstyle (method length, cyclomatic complexity)
- **PMD** — CognitiveComplexity (≤ 15) and CPD copy-paste detection
- **Architecture** — ArchUnit: no field injection, no Lombok, no zero-arg system-clock calls
  (`Instant.now()` etc. — time must come from an injected `Clock`); `ApplicationModules.verify()`
- **Tests** — unit tests + Testcontainers integration tests (real Postgres, real Keycloak)
- **Coverage** — JaCoCo, 80% line minimum (generated code excluded)

Run it with `scripts/gate.sh` (equivalent to `mvn spotless:apply && mvn verify`).

CI (GitHub Actions, runs on push to a published remote) adds, on top of the local gate:

- **CodeQL** — semantic SAST
- **Semgrep** — `p/java`, `p/spring`, `p/owasp-top-ten` rulesets
- **gitleaks** — secret scanning
- **Trivy** — CRITICAL/HIGH vulnerability scan of the built container image

### Deferred (tracked, not forgotten)

- **OWASP Dependency-Check** — needs an NVD API key; wired in on first publish, not run today
- **Live gitleaks verification** — the CI job is configured (see [`.github/workflows/ci.yml`](.github/workflows/ci.yml))
  but hasn't run against a real push yet; this repo has no remote configured as of this commit
- **PIT mutation testing** — planned as a PR/nightly job once a baseline threshold is set
- **Swagger/OpenAPI UI** — `/api/v1/orders` exists now, but springdoc/Swagger UI wiring is still
  pending (spec §8); the endpoints are exercised via `scripts/api.sh` today
- **Loki log shipping** — Loki is provisioned as a Grafana data source and runs in compose, but
  no shipper (e.g. Promtail) forwards app logs into it yet; app logs are structured JSON to
  stdout only today. Tracked for Phase 2
- **CONTRIBUTING.md, SECURITY.md, CI badge** — spec §11 items; not created in Phase 1A

## What's not here yet

Phase 1B delivered the walking-skeleton slice (one order fills end-to-end). Explicitly out of
scope for this commit:

- **Market + cancel orders** — Phase 1B is LIMIT-only with partial fills; market orders and
  cancel are deferred ([ADR-0004](docs/adr/0004-synchronous-engine-single-writer-deferred.md))
- **OWASP Dependency-Check** and a live/verified gitleaks run — need an NVD API key and a
  publish target respectively; both wire in when this repo is pushed to GitHub
- **Swagger/OpenAPI UI** — springdoc wiring for the new `/api/v1/orders` endpoints (spec §8)
- Reconciliation reports the latest run only (no historical drift storage) and does not remediate
  drift or page on it — alerting on the emitted gauges is a deploy-time concern.

## Stack

Java 25, Spring Boot 4.1, Spring Modulith 2.1, Spring Data JDBC, Flyway, PostgreSQL 18,
Spring Security (OAuth2 resource server) + Keycloak, Micrometer + OpenTelemetry, Maven.
Full version rationale in [the design spec](docs/superpowers/specs/2026-07-06-brokerage-oms-design.md#2-stack-versions-verified-2026-07-06-against-repo1mavenorg-metadata).
