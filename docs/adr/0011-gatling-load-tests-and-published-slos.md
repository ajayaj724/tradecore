# ADR-0011: Gatling load tests and published SLOs

- Status: Accepted
- Date: 2026-07-09
- Phase: 3B

## Context

Phase 3 ("the real world") calls for load tests with published SLOs against the synchronous order
path (`POST /api/v1/orders` -> security -> risk -> matching engine -> response). The engine is a
single-writer synchronous component (ADR-0004), so latency under sustained concurrency is a real
question, and we want an executable, repeatable harness plus documented performance targets that
double as a regression guard.

## Decisions

**Gatling with the Java DSL, driven by `gatling-maven-plugin`.** Gatling is the tool named in the
design spec (§Testing). We use its Java DSL (not Scala) so simulations stay in the project's only
language and need no extra toolchain. Versions verified 2026-07-09 against `repo1.maven.org`
maven-metadata `<release>`: `gatling-maven-plugin` 4.21.8, `gatling-charts-highcharts` 3.15.1. A
throwaway spike first confirmed both run on JDK 25 before adopting them (same de-risking pattern as
the Phase 3A Resilience4j-on-Boot-4.1 spike).

**The whole load-test stack is isolated behind a `gatling` Maven profile.** The default build and CI
quality gate never resolve Gatling or compile a simulation. Simulations live in `src/gatling/java`,
which is *not* a compile source root by default; the `gatling` profile adds it as a test-source root
via `build-helper-maven-plugin` (3.6.1, verified 2026-07-09) only when active. Rejected alternatives:
putting simulations in `src/test/java` (would pull Gatling onto every build's test classpath and
run Error Prone / Checkstyle / coverage over them) or as a Scala source set (adds a second language
and compiler). Java simulations must be compiled by the standard Maven compiler — the plugin's own
`simulationsFolder`/compile step is Scala/Kotlin only — which is why a plain `simulationsFolder`
pointing at a non-source folder fails with `ClassNotFoundException`; `add-test-source` is the fix.

**Load tests run manually / nightly, never in the PR gate.** A 60-second ramp is too slow and too
environment-sensitive for every push, and needs a live app + Postgres + Keycloak rather than the
gate's Testcontainers. Run it explicitly:
`mvn -Pgatling gatling:test -Dgatling.simulationClass=gatling.OrderLoadSimulation` against a running
app. Everything (base URL, Keycloak coordinates, rate, duration, SLO thresholds) is overridable via
`-D` so the one file serves a laptop smoke run and a larger soak.

**One shared password-grant token; 201 counts as success.** The simulation fetches a single Keycloak
token once (plain `java.net.http.HttpClient`) and shares it across all virtual users — token
issuance is not what we are load-testing. Each request carries a unique `Idempotency-Key` (so every
call is a genuine new order, never a dedup replay) and a 50/50 BUY/SELL mix crossing at a common
price to exercise the engine. The status check asserts `201`, which covers both `ACCEPTED` and the
domain `REJECTED` outcome (a pre-trade risk rejection is a normal 201, not a fault — ADR/README); any
other status is a real failure.

**Published SLOs (reference local stack), asserted in-run and build-failing.** Calibrated over two
runs at 50 orders/s for 60 s (3,000 requests, 0 failures):

| Metric | SLO | Observed |
|---|---|---|
| `POST /api/v1/orders` p50 | < 25 ms | 5–6 ms |
| `POST /api/v1/orders` p99 | < 100 ms | 14–59 ms |
| Failed (non-2xx) | < 0.1 % | 0.0 % |
| Sustained throughput | ≥ 50 orders/s | 50 orders/s |

The first requests of a run pay JIT/GC warmup (single-request max observed 70–167 ms across runs),
which is why the p99 threshold carries roughly 2x headroom over the worst observed p99 rather than
hugging it — the guard should catch a genuine regression, not warmup jitter. These are targets on the
documented reference environment (local Docker stack on a developer laptop), not absolute production
numbers; the durable value is the repeatable harness, the methodology, and the regression signal.

## Consequences

- A committed, runnable performance harness and a documented SLO baseline; re-runnable after any
  change to the order path to catch latency/throughput regressions.
- Gatling stays entirely out of the default `mvn verify` — the gate's dependency surface, runtime,
  and coverage numbers are unchanged (verified: `mvn verify` green with the profile present but
  inactive).
- The load test needs a live platform. During Phase 3B calibration the compose Postgres 18 service
  failed to start because `compose.yaml` mounts the data volume at the pre-18 path
  (`/var/lib/postgresql/data`); calibration used an ephemeral standalone `postgres:18`. That compose
  defect is tracked separately (it also breaks `scripts/run.sh`) and is not fixed here to keep this
  change scoped to load testing.

## Follow-ups (not blocking)

- Capacity exploration above 50 orders/s (find the knee of the single-writer engine) and a longer
  soak; both are parameter changes to the same simulation.
- Optionally publish results to the Grafana board / a CI artifact once a nightly job exists.
