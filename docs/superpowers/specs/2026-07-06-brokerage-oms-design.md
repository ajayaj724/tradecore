# tradecore — Enterprise Brokerage OMS: Design Spec

- **Date:** 2026-07-06
- **Status:** Approved design, pre-implementation
- **Author:** Ajay Antony (with Claude)
- **Working name:** `tradecore` (final name decided before GitHub publish)

## 1. Purpose & positioning

A production-grade, open-source retail brokerage backend — the flagship portfolio project
demonstrating senior/staff-level engineering: modular architecture with enforced boundaries,
event-driven consistency, a from-scratch matching engine, and full enterprise cross-cutting
concerns (security, observability, resilience, auditability).

Runs entirely locally with `docker compose up`. No cloud dependencies. A reviewer must be able
to place an order and watch it flow to a balanced ledger within 90 seconds of cloning.

**Growth path (each a separate design cycle later):** Next.js trading dashboard → AI/MCP layer
(agent places and analyzes trades through the same API).

## 2. Stack (versions verified 2026-07-06 against repo1.maven.org metadata)

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 25 (LTS) | Virtual threads on; records, sealed interfaces, pattern matching used throughout |
| Framework | Spring Boot 4.1.x | 4.1.0 GA 2026-06-25 |
| Modularity | Spring Modulith 2.1.x | 2.1.0 GA 2026-06-11, tracks Boot 4.1; boundary verification, Event Publication Registry, doc generation |
| Security | Spring Security OAuth2 resource server + Keycloak (compose) | Real JWTs from day one |
| Database | PostgreSQL 18 | Single instance, module-owned schemas |
| Data access | Spring Data JDBC (no Hibernate/JPA) | Flat aggregates, explicit load/save, no lazy loading or dirty checking on money paths (ADR) |
| Migrations | Flyway (Boot-BOM-managed version) | Plain SQL, versioned, roll-forward only |
| Build | Maven | Enterprise-standard; base package `io.github.ajayaj724.tradecore` |
| Observability | Micrometer + OpenTelemetry → OTel Collector → Prometheus / Tempo / Loki / Grafana | App emits OTLP only |
| Resilience | Resilience4j, Bucket4j | External boundaries + API edge |
| Testing | JUnit (Boot-managed), Testcontainers, `@ApplicationModuleTest`, jqwik property tests, Gatling (Phase 3), JMH (engine) | |
| CI | GitHub Actions | Full quality gate (§8) |

**Version policy (ADR):** always the latest stable GA of the Boot + Modulith pair and every
other dependency, verified against the official source at upgrade/commit time —
`repo1.maven.org` maven-metadata.xml or official release notes as ground truth (Context7 and
search indexes can lag). Milestones/RCs require an ADR. Boot-BOM-managed versions are never
overridden.

**Explicitly excluded, with adoption triggers (each an ADR):** Kubernetes, Terraform,
microservices, Kafka-by-default, Liquibase. Infra stays proportionate to the problem.

## 3. Architecture

Four composed patterns:

1. **Modular monolith** (Spring Modulith) — single deployable, six modules, boundaries enforced
   by `ApplicationModules.verify()` running as a test.
2. **Event-driven integration with transactional outbox** — cross-module communication via
   persisted domain events (Event Publication Registry in Postgres). Exception: `orders → risk`
   is a synchronous call because blocking is the correct semantics for pre-trade checks.
3. **Hexagonal at the venue boundary** — `ExecutionVenue` port; adapters: embedded matching
   engine (default) and Upstox/Kite (opt-in, profile-gated, circuit-broken).
4. **Single-writer matching engine** — one thread per symbol's order book; framework-free pure
   Java; deterministic; LMAX-inspired.

### Module map

| Module | Owns | Integration |
|---|---|---|
| `orders` | Order lifecycle state machine (NEW → ACCEPTED → FILLED / PARTIALLY_FILLED / REJECTED / CANCELLED), idempotency keys | Sync call to `risk`; publishes `OrderAccepted`, `OrderRejected` |
| `risk` | Pre-trade checks: cash sufficiency (buys), holdings sufficiency (sells) | Exposes sync RiskCheck API |
| `execution` | `ExecutionVenue` port + adapters | Consumes `OrderAccepted`; publishes `TradeExecuted` |
| `marketdata` | Last price, book-depth snapshots, tick stream | Publishes `PriceUpdated` |
| `portfolio` | Positions, average cost, realized/unrealized P&L | Consumes `TradeExecuted`, `PriceUpdated` |
| `ledger` | Double-entry cash accounting; every posting balances | Consumes `TradeExecuted` |

Module rules: each module owns its Postgres schema; no module reads another module's tables;
public API of a module = its exposed Java API + its published events. Violations fail the build.

### Event semantics

- Publication persisted in the publishing transaction (outbox); replayed on listener failure.
- Delivery is **at-least-once** → every consumer is idempotent (dedup on event id); this is
  covered by tests.
- Future: `@Externalized` events to Kafka when a real external consumer exists (documented
  trigger in ADR, not speculative infrastructure).

## 4. Matching engine

- Price-time priority limit order book per symbol; market + limit orders; partial fills.
- Single writer per symbol via per-symbol queue; no locks in the matching hot path.
- Pure Java module (no Spring imports) → property-testable (jqwik) and benchmarkable (JMH).
- Core invariants (property tests): book never crosses; no fill at a price worse than the
  limit; quantity conservation (placed = filled + open + cancelled); FIFO within price level.

## 5. Data & correctness

- Money as `BIGINT` minor units (paise). Never floating point. Quantities as `BIGINT`.
- Spring Data JDBC repositories — aggregates load and save explicitly; bespoke SQL through
  `JdbcClient`. No ORM session, flush timing, or lazy loading between the code and the money.
- Optimistic locking (`@Version`) on mutable aggregates; pessimistic `@Lock` /
  `SELECT ... FOR UPDATE` where two orders race for the same cash balance.
- Client-supplied `Idempotency-Key` on order submission; duplicates return the original order.
- Immutable audit trail: who/what/when for every state change, written in the same transaction,
  recording the authenticated principal.
- End-of-day reconciliation job proving `ledger cash + position value ≡ account equity`; drift
  emits an alarming metric.
- All schema changes through Flyway from the first table.

## 6. Security

- OAuth2 resource server validating Keycloak-issued JWTs (Keycloak ships in compose with a
  pre-configured realm and demo users per role).
- Roles: `TRADER` (own orders/portfolio only), `OPS` (read everything, cancel anything),
  `ADMIN` (reference data: symbols, trading calendar).
- Method-level authorization; ownership checks in service layer; no endpoint unauthenticated
  except health/readiness and OpenAPI docs.

## 7. Observability & resilience

- OTel traces span module boundaries **and** async event listeners — one trace from
  `POST /orders` to the ledger posting.
- Structured JSON logs with correlation ids (`X-Correlation-Id` accepted or generated).
- Grafana provisioned dashboards: order throughput, fill latency p50/p99, risk rejection rate,
  event-registry lag, JVM/virtual-thread health.
- Resilience4j on the Upstox adapter (timeout, retry with backoff, circuit breaker — metrics
  exposed); Bucket4j rate limiting at the API edge; graceful shutdown drains in-flight orders.

## 8. API contract & CI quality gate

- `/api/v1`, OpenAPI 3.1 via springdoc, Swagger UI enabled.
- Errors: RFC 9457 Problem Details, always. Documented idempotency and pagination semantics.
- **Local machine gate** (`mvn verify`, kept fast): Spotless format, Error Prone + NullAway
  (JSpecify null-safety — matching Spring Framework 7's own annotations), Checkstyle
  structural rules (method length, cyclomatic complexity), ArchUnit framework-rules suite
  (constructor injection only, no field `@Autowired`, controllers never touch repositories,
  package-private by default), unit + Testcontainers integration tests,
  `ApplicationModules.verify()`, JaCoCo coverage gate (80% line, generated code excluded).
- **CI additions** (GitHub Actions): CodeQL semantic SAST (free for public repos), Semgrep
  OSS (`p/java`, `p/spring`, `p/owasp-top-ten`), gitleaks secret scanning, OWASP
  Dependency-Check, Trivy scan of the multi-stage non-root image, SonarCloud quality gate
  (cognitive complexity, security hotspots, public badge).
- **PR/nightly:** PIT mutation testing — enforces assertion strength mechanically; threshold
  documented once baselined.
- ADR: SpotBugs/FindSecBugs deliberately excluded — near-total overlap with CodeQL + Semgrep,
  and bytecode analyzers lag new Java releases; revisit only if CodeQL becomes unavailable.
- Tool versions pinned and Context7-verified at Phase 1 scaffolding time.

## 9. Scope

**v1 in:** equities, market + limit day orders, partial fills, cancel (full), the six modules,
all cross-cutting pillars above.

**v1 out (documented future work, not accidents):** short selling, derivatives, GTC orders,
partial cancels/amends, corporate actions, multi-currency, real user registration (Keycloak
demo users suffice until the dashboard phase).

## 10. Delivery phases

1. **Phase 1 — walking skeleton (tracer bullet):** repo scaffolding, compose stack (Postgres,
   Keycloak, OTel + Grafana), CI gate, `orders` + `risk` + `execution` with embedded engine —
   one order fills end-to-end through security, tracing, Problem Details, audit, idempotency.
2. **Phase 2 — the money story:** `portfolio` + `ledger` + `marketdata`, reconciliation job,
   Grafana dashboards fleshed out.
3. **Phase 3 — the real world:** Upstox adapter behind Resilience4j, Gatling load tests with
   published SLOs, JMH engine benchmarks, hardening.
4. **Later (separate designs):** Next.js dashboard; AI/MCP layer.

## 11. Showcase artifacts checklist

- README with 90-second demo walkthrough, architecture diagram (Modulith-generated C4 +
  PlantUML), CI badge, Grafana screenshot.
- `docs/adr/` — every decision in §2–§3 and every exclusion in §2 gets an ADR.
- `CONTRIBUTING.md`, `SECURITY.md`, conventional commits, semantic releases.
