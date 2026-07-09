# Contributing to tradecore

Thanks for your interest. tradecore is an enterprise brokerage OMS built as a Spring Modulith
modular monolith; the design rationale is in
[`docs/superpowers/specs/2026-07-06-brokerage-oms-design.md`](docs/superpowers/specs/2026-07-06-brokerage-oms-design.md).
Read it before proposing architectural changes.

## Getting set up

Requires **JDK 25**, **Docker** (for the compose platform and Testcontainers), and **Maven**.

```bash
scripts/up.sh     # Postgres, Keycloak (realm pre-imported), OTel + Grafana stack
scripts/run.sh    # run the app (auto-starts the platform if down)
scripts/api.sh METHOD PATH [json] [user]   # authenticated call (auto Idempotency-Key on POST)
```

## Workflow

- **Test-driven.** Write the failing test first; watch it fail; then write the minimal code. Every
  feature and bugfix ships with tests. Test names describe behavior (`rejectsBuyWhenCashInsufficient`).
- **Run the full gate before every commit:** `scripts/gate.sh` (`mvn spotless:apply` then `mvn verify`).
  It must be green — no scoping, no skip flags. The gate runs formatting, Error Prone/NullAway,
  Checkstyle, PMD, unit + Testcontainers integration tests, `ApplicationModules.verify()`, and an 80%
  JaCoCo line-coverage floor. If Docker isn't running, the integration tests fail — start it, don't skip.
- **Conventional commits** (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `build:`, `ci:`).
- **One ADR per architectural decision** in [`docs/adr/`](docs/adr/), in the same PR.
- Feature branch, then a `--no-ff` merge to `main`.

## Non-negotiable invariants

These are enforced by the gate; fix the design rather than weakening the rule.

- **Money is `BIGINT` minor units (paise).** Never `double`/`float`/`BigDecimal` for money in the
  domain or schema; convert to display units only at the API edge.
- **Module boundaries are law.** No module reads another's tables or internal classes. Cross-module
  is an exposed API (only `orders → risk`, synchronous) or a published domain event.
  `ApplicationModules.verify()` fails the build on a breach.
- **Every event consumer is idempotent** (at-least-once delivery). Any new `@ApplicationModuleListener`
  ships a duplicate-delivery test in the same PR.
- **The matching engine stays framework-free** (no Spring in `execution.engine`). Engine changes need
  jqwik property tests for the affected invariant.
- **All schema changes via Flyway, roll-forward only.** Never edit an applied migration; add a new one.
- **Errors are RFC 9457 Problem Details.** No naked exceptions or ad-hoc error JSON.
- **Deterministic time.** Inject `java.time.Clock`; never zero-arg `Instant.now()` / `System.currentTimeMillis()`.
- **No Lombok**; records and compact constructors instead. Constructor injection only.

## Dependencies

Prefer Boot-BOM-managed versions; never pin a version the BOM already manages. Any explicit version is
verified against the official source (repo1.maven.org metadata / release notes) at commit time — never
from memory. Latest stable GA only; milestones/RCs require an ADR. New dependencies are justified in the
PR and must pass the security gates (see [SECURITY.md](SECURITY.md)).

## Opening a PR

Green gate locally, then push and open the PR. CI re-runs the gate plus CodeQL, Semgrep, gitleaks, and
Trivy. Describe what changed and why, link the ADR if there is one, and note any new dependency.
