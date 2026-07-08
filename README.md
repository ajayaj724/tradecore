# tradecore

An enterprise brokerage order-management system, built as a modular monolith on Spring Boot 4
and Spring Modulith. This repository is the **Phase 1A chassis**: the platform, security,
observability, and quality-gate scaffolding that every domain module (orders, risk, execution,
matching engine, ...) will be built on in the phases that follow. Full design rationale lives
in [`docs/superpowers/specs/2026-07-06-brokerage-oms-design.md`](docs/superpowers/specs/2026-07-06-brokerage-oms-design.md).

> **Status:** Phase 1A complete. No domain/trading functionality exists yet — see
> [What's not here yet](#whats-not-here-yet) before opening an issue about a missing feature.

## Demo credentials — local only

The compose stack ships a pre-imported Keycloak realm with three throwaway users. These
exist **only** in the local `docker compose` stack, are never used outside it, and are
intentionally allowlisted in [`.gitleaks.toml`](.gitleaks.toml) as non-secrets.

| User | Password | Role |
|---|---|---|
| `trader1` | `demo` | `TRADER` |
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
curl -si http://localhost:8080/api/v1/x
# HTTP/1.1 401
# Content-Type: application/problem+json;charset=ISO-8859-1
# {"detail":"Authentication required","instance":"/api/v1/x","status":401,"title":"Unauthorized"}

# same path, with a bearer token — 404 (no domain endpoints exist yet in Phase 1A)
TOKEN=$(scripts/token.sh)
curl -si http://localhost:8080/api/v1/x -H "Authorization: Bearer $TOKEN"
# HTTP/1.1 404
# {"detail":"No static resource api/v1/x.","instance":"/api/v1/x","status":404,"title":"Not Found"}
```

That 404 is expected and correct: the security chain, JWT validation, and Problem Details
error mapping are all real; there is simply no `/api/v1/x` (or any domain) endpoint behind
them yet.

Other scripts: `scripts/down.sh [--wipe]` stops the platform (`--wipe` also drops volumes),
`scripts/api.sh METHOD PATH [json] [user]` makes an authenticated call with an
auto-generated `Idempotency-Key` (POST) and `X-Correlation-Id`, `scripts/gate.sh` runs the
full local quality gate, `scripts/logs.sh [service]` tails compose logs, `scripts/psql.sh`
opens a database shell.

## Architecture

Single deployable, Spring Modulith-verified. At this commit the codebase is one application
module (`io.github.ajayaj724.tradecore`, plus its `config` package for security and error
handling) — there are no domain modules yet. `ApplicationModules.verify()` runs as a test
(`ModularityTests`) on every build, so as domain modules are added in Plan 1B, boundary
violations fail the gate rather than getting caught in review.

The same test class generates Modulith's architecture documentation (C4 component diagram,
module dependency diagram) from the live code:

```bash
mvn test -Dtest=ModularityTests
# output: target/spring-modulith-docs/ (components.puml, module-config.puml, all-docs.adoc)
```

These are build output, not committed — regenerate them locally, or render the `.puml`
files with any PlantUML viewer. The target module map (`orders`, `risk`, `execution`,
`marketdata`, `portfolio`, `ledger`) and the event-driven integration pattern between them
are documented in the [design spec, §3](docs/superpowers/specs/2026-07-06-brokerage-oms-design.md#3-architecture)
— none of those modules exist in this repository yet.

Architecture decisions are recorded as ADRs in [`docs/adr/`](docs/adr/):

- [0001 — transactional outbox and redelivery](docs/adr/0001-transactional-outbox-and-redelivery.md):
  cross-module events go through the Spring Modulith Event Publication Registry, a
  Flyway-owned Postgres outbox, proven by an integration test today even though no
  publishing module exists yet.
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

Dashboards (order throughput, fill latency, risk rejection rate, event-registry lag) are not
built yet — there is no traffic to chart until Plan 1B's domain modules exist. Tracked for
Phase 2.

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
- **Swagger/OpenAPI UI** — lands with the first real endpoint (`/api/v1/...`) in Plan 1B; there is
  nothing to document yet
- **Loki log shipping** — Loki is provisioned as a Grafana data source and runs in compose, but
  no shipper (e.g. Promtail) forwards app logs into it yet; app logs are structured JSON to
  stdout only today. Tracked for Phase 2
- **CONTRIBUTING.md, SECURITY.md, CI badge** — spec §11 items; not created in Phase 1A

## What's not here yet

Phase 1A is infrastructure only. Explicitly out of scope for this commit:

- **Domain modules** — `orders`, `risk`, `execution`, the matching engine (Plan 1B)
- **`portfolio`, `ledger`, `marketdata`** modules and end-of-day reconciliation (Phase 2)
- **Grafana dashboards** — data sources are provisioned; the dashboards themselves are Phase 2
- **OWASP Dependency-Check** and a live/verified gitleaks run — need an NVD API key and a
  publish target respectively; both wire in when this repo is pushed to GitHub
- **Swagger/OpenAPI UI** — no API exists yet to document

## Stack

Java 25, Spring Boot 4.1, Spring Modulith 2.1, Spring Data JDBC, Flyway, PostgreSQL 18,
Spring Security (OAuth2 resource server) + Keycloak, Micrometer + OpenTelemetry, Maven.
Full version rationale in [the design spec](docs/superpowers/specs/2026-07-06-brokerage-oms-design.md#2-stack-versions-verified-2026-07-06-against-repo1mavenorg-metadata).
