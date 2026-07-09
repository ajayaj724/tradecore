# tradecore Phase 3A — Upstox Market-Data Adapter behind Resilience4j: Design Spec

- **Date:** 2026-07-09
- **Status:** Approved (brainstorming) — pending implementation plan
- **Extends:** [`2026-07-06-brokerage-oms-design.md`](2026-07-06-brokerage-oms-design.md) §10.3 Phase 3 (slice 3A of 3)
- **Builds on:** Phase 2 (marketdata `last_price`, `PriceUpdated` event, reconciliation equity, the 2C observability layer)
- **Branch:** `feat/phase3a-upstox-marketdata`

## 1. Purpose & scope

The first slice of Phase 3 ("the real world"): replace/augment internal-trade-derived prices with a
**live external market-data feed**, wrapped in **Resilience4j** so the OMS degrades gracefully when
the upstream is slow or unavailable. This is the resilience showcase — a real outbound HTTP
integration whose failure modes (latency, errors, outage) are handled with timeout, retry, circuit
breaking, and a safe fallback, all observable.

### 1.1 In scope

- A new **inbound market-data adapter inside the `marketdata` module**: a `@Scheduled` poller that
  fetches the last-traded price (LTP) of configured symbols from an Upstox-shaped HTTP API via a
  Spring `RestClient`, decorated with **Resilience4j** (TimeLimiter → Retry → CircuitBreaker) and a
  **fallback**.
- On a successful poll: update `marketdata.last_price` and publish `PriceUpdated` — reusing the
  exact existing downstream path (portfolio marks, reconciliation equity).
- A **WireMock "Upstox"** modeling the real HTTP/DTO contract, with **fault injection** driving
  integration tests that prove each resilience behavior.
- **Observability**: Resilience4j's Micrometer metrics (circuit state, call outcomes) plus a
  **feed-staleness gauge** (seconds since last successful poll), surfaced on the existing Grafana
  board.

### 1.2 Out of scope

- **WebSocket streaming** — pull/poll only (request/response is the natural fit for circuit
  breaking; streaming's reconnect/backoff model is a different design).
- **Real Upstox credentials / live trading** — the adapter is coded to the real contract but driven
  by a stub; the base URL is config-driven so it *can* point at the real sandbox later, but no live
  wiring, OAuth flow, or account is in scope.
- **Order execution via Upstox** — this slice is market-data only; the embedded matching engine
  remains the execution core.
- **Multi-provider failover / price-source arbitration** — see §7 coexistence (last-writer-wins is
  accepted for the demo).
- **Persisting historical quotes / a quote audit** — the feed updates the latest price only.

## 2. Decisions locked in brainstorming

| # | Fork | Decision | Rationale |
|---|---|---|---|
| 1 | Slice order | **3A first** (Upstox adapter), then 3B (Gatling) / 3C (JMH) | The "real world" headline; highest interview signal (resilience patterns) |
| 2 | Adapter role | **Live market-data feed** (not order execution) | Cleanest resilience showcase; preserves the embedded engine; slots into existing `marketdata` |
| 3 | Upstream | **Stubbed Upstox (WireMock) modeling the real HTTP/DTO contract** | Real-contract fidelity + deterministic fault injection + no creds; config-driven base URL to point at the real sandbox later |
| 4 | Ingest model | **Scheduled HTTP poll** of LTP for configured symbols | Request/response is the natural fit for circuit breaking; deterministic to demo; reuses the `@Scheduled` pattern from 2C |
| 5 | Resilience stack | **TimeLimiter → Retry → CircuitBreaker + Fallback** | The textbook outbound-call resilience set |
| 6 | Fallback | **Do NOT publish; retain last-known price**, surface a degraded/staleness signal | Never feed a stale/garbage price into risk/equity |
| 7 | Module placement | **Inside `marketdata`** as an inbound adapter (hexagonal port/adapter) | Keeps "prices" (internal + external) one module's concern; reuses the `last_price` + `PriceUpdated` path |
| 8 | Coexistence | External feed and internal-trade updates both write `last_price` — **last-writer-wins** | Conscious demo tradeoff; price-source arbitration is out of scope |

## 3. Architecture & data flow

The adapter is an **inbound port/adapter within `marketdata`**:

- **`UpstoxClient`** — a thin wrapper over a Spring `RestClient` that calls the Upstox LTP endpoint
  for a symbol (or batch) and maps the response DTO to a `long` paise price. Base URL, and the
  symbol→instrument-key mapping, come from configuration.
- **`UpstoxPriceFeed`** — a `@Scheduled(fixedDelay)` component that, per configured symbol, invokes
  the Resilience4j-decorated client call, and on success calls `MarketDataService` to apply the
  external price.
- **`MarketDataService`** gains an inbound method (e.g. `applyExternalPrice(symbol, priceInPaise,
  observedAt)`) that upserts `marketdata.last_price` and publishes `PriceUpdated` — the same effect
  as the existing `onTrade(...)` path (`MarketDataService.java:28,41`), so nothing downstream
  changes.

Data flow (success): `@Scheduled` poll → `UpstoxClient.ltp(symbol)` [Resilience4j] → HTTP GET
Upstox → DTO → paise → `MarketDataService.applyExternalPrice` → upsert `last_price` + publish
`PriceUpdated` → `PortfolioListener.onPrice` marks positions; `reconciliation` equity reads the new
`lastPrice`.

Data flow (degraded): poll → client call times out / errors / circuit open → **fallback** →
last-known `last_price` retained, **no** `PriceUpdated` published, staleness signal advances.

Money stays `long` paise end-to-end; the Upstox DTO's decimal price is converted to paise at the
adapter boundary (the one place external units enter). No `double`/`BigDecimal` past the boundary.

## 4. Resilience behavior

Each outbound LTP call is decorated (order matters):

1. **TimeLimiter** — bounds the call; a slow Upstox response is abandoned rather than blocking the
   poller.
2. **Retry** — a small bounded retry on transient failures (timeout, 5xx, connection reset), with
   backoff; exhausted retries fall through to the circuit breaker's failure accounting.
3. **CircuitBreaker** — opens under a sustained failure rate; while open, calls short-circuit to the
   fallback immediately (no upstream hit); half-open probes close it on recovery.
4. **Fallback** — retain last-known price (§2.6), publish nothing, advance the staleness signal.

All thresholds (timeout duration, retry count/backoff, failure-rate window, wait-duration-in-open)
are **configuration**, defaulted for the demo. Deterministic time: any duration/age is computed from
the injected `Clock`; no zero-arg `now()`.

## 5. The stub + testing strategy (TDD)

The adapter targets Upstox's **real** HTTP path + JSON DTO shape, but tests drive a **WireMock**
server (a new test-scope dependency) that models it. `RestClient`'s base URL points at the WireMock
port (`@DynamicPropertySource`). Fault-injection integration tests, each asserting a specific
behavior:

- **Happy path** — WireMock returns a price → an IT observes `PriceUpdated` propagate and
  `marketdata.last_price` / a portfolio mark update (Awaitility on the event pipeline).
- **Timeout** — WireMock delays past the TimeLimiter → the call is abandoned, fallback engages, last
  price retained, no `PriceUpdated`.
- **Circuit opens** — repeated WireMock 5xx beyond the failure threshold → CircuitBreaker transitions
  to OPEN (assert via the Resilience4j registry / its metric), subsequent calls short-circuit.
- **Recovery** — after the wait duration, WireMock returns healthy → half-open probe closes the
  circuit, prices flow again.
- **Unit** — DTO→paise conversion (including rounding/precision at the boundary) as a focused unit
  test.
- `ApplicationModules.verify()` stays green; JaCoCo ≥ 80%.

Tests must be deterministic under the shared Testcontainers Postgres (dedicated symbols not touched
by sibling ITs; assert deltas/known values, per the 2C test-isolation lesson).

## 6. Observability (extends 2C)

- **Resilience4j Micrometer metrics** — the library publishes `resilience4j_circuitbreaker_state`,
  call counts by outcome, etc.; enable the binder so they reach `/actuator/prometheus`.
- **Feed staleness** — a gauge `tradecore_marketdata_feed_staleness_seconds` = seconds since the last
  successful poll (Clock-based), so a stuck/degraded feed is visible even when the circuit metric is
  quiet.
- **Grafana** — add panels to the existing `tradecore` board: circuit-breaker state and feed
  staleness. (Grafana provider reload caveat from 3A verification: a running Grafana needs a restart
  to pick up board changes — note in the runbook.)

## 7. Coexistence & boundary notes

- Both the external feed and the internal-trade path (`onTrade`) upsert `last_price`
  (**last-writer-wins**). For symbols the feed covers, its polls dominate at the poll cadence; trades
  between polls still move the price. Accepted for the demo; arbitration is out of scope (§1.2).
- `marketdata` gains an **outbound dependency** (HTTP client + Resilience4j) — it becomes a real
  adapter module, not just a read-model. It still exposes no new cross-module *sync* call; the feed
  is internal to the module and communicates outward only via the existing `PriceUpdated` event, so
  `ApplicationModules.verify()` is unaffected.

## 8. Dependencies (risk retired by spike — 2026-07-09)

The load-bearing unknown — "does Resilience4j work on Boot 4.1?" — was **settled by a throwaway
spike**: on Boot 4.1 / Java 25 the circuit breaker autoconfigured, opened after failures,
short-circuited to its fallback, and published Micrometer metrics (`BUILD SUCCESS`). The core-decorator
hedge is **not** needed. Confirmed working set:

- **`RestClient`** — Spring-managed (`spring-web`), no new dependency.
- **`io.github.resilience4j:resilience4j-spring-boot4:2.4.0`** — the purpose-built Boot 4 starter
  (verified latest GA on Maven Central; transitively pulls `resilience4j-spring6`, `-annotations`,
  `-micrometer`). The **annotation-driven** path works: `@CircuitBreaker`/`@Retry`/`@TimeLimiter`
  with `fallbackMethod`, thresholds via `resilience4j.circuitbreaker.instances.<name>.*` properties.
- **`org.aspectj:aspectjweaver`** (BOM-managed — no explicit version) — **REQUIRED**. Resilience4j's
  annotations are `@Aspect`-based, and **`spring-boot-starter-aop` has no 4.x GA (only `4.0.0-M2`)**,
  so it is not in the Boot 4.1 BOM. Adding `aspectjweaver` directly is what activates Boot's AOP
  autoproxy so the aspects weave. Symptom if omitted: the aspect silently doesn't fire and the raw
  method throws (verified — this was the spike's first failure mode).
- **WireMock** — new, **test scope**. Use the **3.x GA** line, NOT `4.0.0-beta.*` (Central's latest is
  a beta); pin the exact 3.x `<release>` at plan time. Justify in the PR; passes OWASP/Trivy.
- No version pinned that the Boot BOM manages; any explicit version (e.g. resilience4j `2.4.0`)
  verified against the official source at commit time (CLAUDE.md).

## 9. Testing strategy summary

Property/unit for the DTO→paise conversion; Testcontainers + WireMock ITs for each resilience
behavior (happy, timeout, circuit-open, recovery); event-propagation assertions via Awaitility;
`verify()` + JaCoCo ≥ 80%; full `mvn verify` green per commit.

## 10. Task breakdown (dependency order — for the plan)

1. **Dependencies** — add the spike-verified set: `resilience4j-spring-boot4:2.4.0` +
   `org.aspectj:aspectjweaver` (BOM-managed) + WireMock 3.x (test scope, pin exact GA); ADR
   recording the annotation-driven-starter choice and the `spring-boot-starter-aop` no-GA gotcha.
2. **`UpstoxClient`** — `RestClient` wrapper + DTO→paise conversion (unit test first).
3. **Resilience4j decoration** — TimeLimiter/Retry/CircuitBreaker + fallback around the client
   (config-driven thresholds).
4. **`UpstoxPriceFeed` + `MarketDataService.applyExternalPrice`** — `@Scheduled` poll wiring;
   success path publishes `PriceUpdated`.
5. **Resilience ITs** — WireMock fault-injection: happy, timeout, circuit-open, recovery.
6. **Observability** — R4j Micrometer binder + feed-staleness gauge + Grafana panels.
7. **Closeout** — ADR(s), README (resilience/feed note), final gate.

## 11. ADRs to write

- **Market-data via a resilient external adapter** — pull/poll behind Resilience4j; retain-last-known
  fallback; last-writer-wins coexistence with internal-trade prices.
- **Resilience4j integration approach** — annotation-driven via `resilience4j-spring-boot4:2.4.0` +
  `aspectjweaver` (spike-verified, §8); records the `spring-boot-starter-aop` no-4.x-GA gotcha.

## 12. Definition of done

- Configured symbols' prices are fed from the (stubbed) Upstox contract into `marketdata.last_price`
  and `PriceUpdated`, moving portfolio marks and reconciliation equity — proven by an IT.
- Timeout, circuit-open, and recovery behaviors are each proven by a fault-injection IT; the fallback
  never publishes a stale price.
- Resilience4j + feed-staleness metrics are exported and a Grafana panel renders them.
- `ApplicationModules.verify()` green; JaCoCo ≥ 80%; ADR(s) written; full `mvn verify` green.
- The base URL is config-flippable to a real Upstox sandbox without code changes (contract fidelity).
