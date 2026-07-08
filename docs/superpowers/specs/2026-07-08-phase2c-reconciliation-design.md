# tradecore Phase 2C — Reconciliation & Observability: Design Spec

- **Date:** 2026-07-08
- **Status:** Approved (brainstorming) — pending implementation plan
- **Extends:** [`2026-07-06-brokerage-oms-design.md`](2026-07-06-brokerage-oms-design.md) §5, §7, §10 Phase 2 (slice 2C of 3)
- **Builds on:** 2A (ledger/cash) + 2B (portfolio/positions) — reconciles their read-models
- **Completes:** Phase 2 ("the money story")
- **Branch:** `feat/phase2c-reconciliation`

## 1. Purpose & scope

The final slice of Phase 2: a **reconciliation job** that proves the event-fed read-models haven't
drifted from their source-of-truth modules, and the **observability** layer (metrics + Grafana
dashboards) that makes the whole system legible. This is where the at-least-once event architecture
earns its keep — reconciliation is the backstop that catches a lost or duplicated event.

### 1.1 In scope

- New **`reconciliation`** module (read-only reporting): compares `risk`'s projected settled cash /
  holdings against `ledger` / `portfolio`, computes per-account equity, emits drift + equity metrics.
- **Instrumentation** the dashboards need: fill-latency `Timer`, risk-rejection counter,
  event-registry-lag gauge (reusing the Micrometer + Prometheus pipeline from Phase 1A).
- **Grafana dashboards** provisioned as JSON: order throughput, fill latency p50/p99, risk rejection
  rate, event-registry lag, reconciliation drift, JVM / virtual-thread health.
- New public `risk` read getters (`settledCash`, `settledHoldings`) so reconciliation can query them.

### 1.2 Out of scope

- **Alerting / paging** (Alertmanager, PagerDuty) — the metrics are emitted; wiring alerts on them is
  a deploy-time concern, not code.
- **Historical drift storage / audit of reconciliation runs** — the gauges reflect the latest run.
- **Automatic remediation** of drift — reconciliation *detects* and alarms; fixing is operator work.
- **Enumerating the full account/symbol universe** — 2C reconciles a configured set (the demo
  accounts/symbols); a reference-data-driven enumeration is a later concern.

## 2. Decisions locked in brainstorming

| # | Fork | Decision | Rationale |
|---|---|---|---|
| 1 | Reconciliation data access | **Read-only reporting module** querying the public read APIs of `ledger`/`portfolio`/`risk`/`marketdata` | Checks projections against the *source of truth* (catches real event drift). Reporting reads are exempt from the trading-path "`orders → risk` only sync" rule, which governs the write path — recorded as an ADR |
| 2 | Cadence | **`@Scheduled`** periodic run (configurable fixed delay), gauges reflect the latest run | Prometheus scrapes continuously; a batch reporting job doesn't need an endpoint. The reconciliation method is directly invocable for tests |
| 3 | Drift metric shape | **Gauge = count of drifted (account, symbol) pairs; 0 when consistent** | A single alarming signal (`> 0` = investigate); per-account equity emitted as tagged informational gauges |
| 4 | Reconciliation universe | **Configured account/symbol set** (default: demo accounts + symbols) | Enumerating all accounts needs reference data; out of scope for the tracer |

## 3. The reconciliation module

A read-only module that, each run, iterates the configured (account, symbol) set and computes:

- **Cash drift** (per account): `risk.settledCash(account) − ledger.balanceOf(account)` — expected 0.
- **Holdings drift** (per account, symbol): `risk.settledHoldings(account, symbol) −
  portfolio.positionQty(account, symbol)` — expected 0.
- **Equity / NAV** (per account): `ledger.balanceOf(account) + Σ_symbol (portfolio.positionQty ×
  marketdata.lastPrice)` — informational.

It emits Micrometer gauges scraped by Prometheus:

- `tradecore_reconciliation_drift_pairs` — count of (account, symbol) pairs with non-zero cash **or**
  holdings drift. **0 = healthy; > 0 alarms.**
- `tradecore_account_equity{account=...}` — computed net asset value per account.

`ReconciliationService.reconcile()` is `@Scheduled(fixedDelayString=...)` and directly callable
(so a test can force a run and assert the gauge).

## 4. Boundary rationale (ADR)

`reconciliation` depends on the **public read APIs** of `ledger`, `portfolio`, `risk`, `marketdata`
— it never reads their tables. It is pure **fan-in**: nothing depends on `reconciliation`, so
`ApplicationModules.verify()` stays acyclic. The design spec makes `orders → risk` the only
synchronous cross-module call on the **trading write path**; a read-only reconciliation/reporting
job is a distinct category, and permitting it to query settled state across modules is recorded as
an ADR (the alternative — every module re-publishing snapshot events for a batch job — is machinery
the tracer doesn't need). Requires two new public getters on `risk`: `settledCash(account)` and
`settledHoldings(account, symbol)`.

## 5. Instrumentation (metrics)

Phase 1A already ships Micrometer + `/actuator/prometheus` + OTel tracing. 2C adds the business
metrics the dashboards visualize:

- **Fill latency** — a `Timer` recorded when an order reaches FILLED (submit → filled duration), for
  p50/p99. Measured with the injected `Clock` (submit timestamp on the order → fill time).
- **Risk rejection rate** — a `Counter` incremented on each `OrderRejected` (tagged by reason).
- **Event-registry lag** — a gauge over `event_publication` rows with `completion_date IS NULL`
  (incomplete publications = consumer backlog / failure).
- **Reconciliation drift** — the gauges from §3.

Metrics use stable names/tags; no zero-arg `Instant.now()` (Clock-based durations).

## 6. Grafana dashboards

Provisioned JSON under `infra/grafana/provisioning/dashboards/` (a dashboard provider + one
`tradecore` board), so `scripts/up.sh` brings them up automatically. Panels:

1. **Order throughput** — orders/sec (rate of the submit counter).
2. **Fill latency** — p50 / p99 from the fill `Timer` histogram.
3. **Risk rejection rate** — rejections/sec (by reason).
4. **Event-registry lag** — incomplete-publication gauge.
5. **Reconciliation drift** — `tradecore_reconciliation_drift_pairs` (should flatline at 0).
6. **JVM / virtual threads** — heap, GC, thread counts.

## 7. Testing strategy (TDD)

- **Consistent state → zero drift**: after a normal fill, `reconcile()` sets
  `tradecore_reconciliation_drift_pairs` to 0 (an IT reads the meter registry).
- **Forced divergence → non-zero drift**: directly perturb `risk.settled_cash` (or `settled_holdings`)
  to diverge from `ledger`/`portfolio`, run `reconcile()`, assert the gauge `> 0`. Proves the backstop
  actually detects a lost/duplicated event's effect.
- **Equity**: per-account equity gauge equals `cash + Σ(qty × last_price)` for a known state.
- **Metric presence**: fill-latency `Timer`, rejection `Counter`, and registry-lag gauge are
  registered and update (a fill records the timer; a rejection increments the counter).
- `ApplicationModules.verify()` green (`reconciliation` fan-in, acyclic); JaCoCo ≥ 80%.
- Grafana dashboard JSON validates (well-formed; provisioning loads without error) — a lightweight
  check, not a rendered-pixel test.

## 8. Task breakdown (dependency order)

1. **`risk` read getters** — public `settledCash(account)` + `settledHoldings(account, symbol)`.
2. **`reconciliation` module** — `ReconciliationService.reconcile()` (drift + equity), `@Scheduled`,
   Micrometer gauges; boundary ADR.
3. **Instrumentation** — fill-latency `Timer`, rejection `Counter`, event-registry-lag gauge.
4. **Grafana dashboards** — provider config + `tradecore` dashboard JSON; validation test.
5. **Closeout** — ADR(s), README (Grafana screenshot / metric note), final gate; **Phase 2 complete**.

## 9. ADRs to write

- **Reconciliation as a read-only reporting module** — allowed to query settled state across modules;
  the trading-path "only `orders → risk` sync" rule governs writes, not read-only reporting.

## 10. Definition of done

- `reconcile()` reports **zero drift** for a consistent system and **non-zero** when a read-model is
  forced to diverge — proven by ITs; drift is a Prometheus gauge.
- Per-account equity (`cash + Σ position×price`) is emitted; fill-latency, rejection, and
  registry-lag metrics are registered and update.
- Grafana dashboards are provisioned and load; the drift panel exists.
- `ApplicationModules.verify()` green; JaCoCo ≥ 80%; ADR written; full `mvn verify` green.
- **Phase 2 is complete** — the money story (cash, positions, P&L, reconciliation, dashboards) is done.
