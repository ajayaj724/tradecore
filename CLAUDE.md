# tradecore — Project Instructions

Enterprise brokerage OMS. Design spec: `docs/superpowers/specs/2026-07-06-brokerage-oms-design.md`.
Read the spec before architectural work; it is the source of truth for scope and decisions.

## Non-negotiable invariants

- **Money is `BIGINT` minor units (paise).** Never `double`/`float`/`BigDecimal` for money in
  domain or schema. Convert to display units only at the API/UI edge.
- **Module boundaries are law.** No module reads another module's tables or internal classes.
  Cross-module = exposed API (sync, `orders → risk` only) or published domain events. If
  `ApplicationModules.verify()` fails, fix the design, never widen the boundary annotation.
- **Every event consumer is idempotent.** The registry delivers at-least-once. Any new
  `@ApplicationModuleListener` ships with a duplicate-delivery test in the same PR.
- **The matching engine stays framework-free.** No Spring imports inside the engine package.
  Engine changes require property tests (jqwik) for the affected invariant.
- **All schema changes via Flyway.** Never edit an applied migration; add a new one.
  Roll-forward only.
- **No unauthenticated endpoints** except health/readiness and OpenAPI docs. Exception:
  `/actuator/prometheus` is unauthenticated locally only — see
  [ADR-0002](docs/adr/0002-actuator-prometheus-scrape-exposure.md); must be closed before
  any non-local deploy.
- **Errors are RFC 9457 Problem Details.** No naked exceptions or ad-hoc error JSON.
- **Deterministic time.** No zero-arg `Instant.now()` / `LocalDateTime.now()` /
  `System.currentTimeMillis()` anywhere, main or test — inject `java.time.Clock` (single
  `Clock.systemUTC()` bean in prod config) and use `Clock.fixed(...)` in tests. `now(Clock)`
  overloads are fine; Awaitility/latch timeouts are synchronization, not time data.
  Enforced by ArchUnit (`noSystemClock`).

## Code style

- Java 25 idioms: records for values/events, sealed interfaces for closed hierarchies
  (e.g., order states/commands), pattern matching over instanceof-chains.
- **No Lombok.** Records and compact constructors cover it; keep bytecode = source.
- Constructor injection only; no field `@Autowired`.
- Domain events are immutable records named in past tense (`TradeExecuted`, not `ExecuteTrade`).
- Test names describe behavior: `rejectsBuyWhenCashInsufficient`, not `testRiskCheck2`.
- Javadoc on module-public API only; no comment noise on internals — code should read clean.

## Workflow

- TDD (superpowers:test-driven-development) for every feature and bugfix.
- Before every commit: run the `tradecore-quality-gate` skill. No commit with a red gate.
- Conventional commits (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `build:`, `ci:`).
- Every architectural decision (or reversal) gets an ADR in `docs/adr/` in the same PR.
- New dependency = justify in the PR description + passes OWASP/Trivy gates. Prefer
  Boot-managed versions; never pin a version the Boot BOM already manages. Any explicit
  version is verified against the official source at commit time (repo1.maven.org
  maven-metadata.xml / official release notes; Context7 where fresh) — never from memory.
  Latest stable GA only; milestones/RCs require an ADR.

## Management scripts (`scripts/`)

Use these instead of retyping raw docker/maven/curl:

- `scripts/up.sh` / `scripts/down.sh [--wipe]` — platform lifecycle (waits for Postgres + Keycloak realm; `--wipe` resets volumes)
- `scripts/run.sh` — run the app (auto-starts the platform if down)
- `scripts/token.sh [user] [pw]` — Keycloak token for demo users (default trader1/demo)
- `scripts/api.sh METHOD PATH [json] [user]` — authenticated API call; auto `Idempotency-Key` on POST, `X-Correlation-Id` always
- `scripts/gate.sh` — the full machine gate (spotless:apply + verify); the command form of the tradecore-quality-gate skill's steps 3–4
- `scripts/logs.sh [service]`, `scripts/psql.sh [args]` — compose logs / database shell

## Verification

- `mvn spotless:apply` to fix formatting, then `mvn verify` must pass locally — it runs the
  same gate as CI (format check, Error Prone, Checkstyle structural rules, unit +
  Testcontainers ITs, module verification, 80% JaCoCo line coverage).
- If Docker isn't running, Testcontainers ITs fail — start Docker first, don't skip tests.
