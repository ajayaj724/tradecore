# Phase 2C — Reconciliation & Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a read-only reconciliation job that proves the event-fed read-models haven't drifted from their source-of-truth modules, plus the business metrics and Grafana dashboards that make the system legible — completing Phase 2.

**Architecture:** A new `reconciliation` module (pure fan-in) queries the public read APIs of `ledger`/`portfolio`/`risk`/`marketdata` on a `@Scheduled` cadence, computes per-account cash/holdings drift and equity, and publishes them as Micrometer gauges. Order-side instrumentation (submit counter, rejection counter, fill-latency timer) and an event-registry-lag gauge feed six provisioned Grafana panels. No schema changes — everything reads existing tables through the modules' public methods.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Modulith 2.1, Spring Data JDBC / `JdbcClient`, Micrometer + `micrometer-registry-prometheus`, PostgreSQL 18, Testcontainers, JUnit 5 + AssertJ + Awaitility, Grafana 13.

## Global Constraints

Copied verbatim from the spec and `CLAUDE.md`; every task's requirements implicitly include these.

- **Money & quantities are `long` minor units (paise) / `BIGINT`.** Never `double`/`float`/`BigDecimal` for money or share quantities.
- **Deterministic time.** No zero-arg `Instant.now()` / `LocalDateTime.now()` / `System.currentTimeMillis()` anywhere (main or test). Inject `java.time.Clock`; `now(Clock)` overloads are fine. Enforced by ArchUnit `noSystemClock` (`ArchitectureRulesTest.java:27-43`).
- **Module boundaries are law.** `reconciliation` may call only the *public* services of other modules; it never reads another module's tables directly (except the module-agnostic `event_publication` infra table). `ApplicationModules.verify()` (`ModularityTests.java:13`) must stay green — `reconciliation` is fan-in (nothing depends on it), so it stays acyclic.
- **No Lombok.** Records for values/config; constructor injection only; no field `@Autowired`.
- **All schema changes via Flyway, roll-forward only.** This slice adds **no migration** (read-only over existing tables). If that changes, the next version is `V11__…`.
- **Every commit runs the `tradecore-quality-gate`** (`scripts/gate.sh` = `mvn spotless:apply` + `mvn verify`) and it must be green: format check, Error Prone, Checkstyle, unit + Testcontainers ITs, `verify()`, **80% JaCoCo line coverage**. No skip/quiet flags. Docker must be running for ITs.
- **Metric names are stable dotted identifiers** (Micrometer converts dots to Prometheus underscores). Dashboards reference the Prometheus form.
- **Conventional commits** (`feat:`, `test:`, `docs:`); **every architectural decision gets an ADR in the same PR** (`docs/adr/NNNN-*.md`).

## Key design decisions resolved during planning

1. **Fill-latency submit timestamp** — `orders.trade_order.created_at` exists but is DB `DEFAULT now()` (not `Clock`-sourced) and is not loaded into the `Order` aggregate. We instead source submit time from the **`Clock`-sourced `orders.audit` `SUBMITTED` row** — deterministic and additive, with **no change to the `Order` aggregate or the write path**. (Alternative, rejected for blast radius: add a `submittedAt` field to the `Order` record.)
2. **Rejection counter placement** — `OrderRejected` is published in `orders`, not `risk`. The counter increments **synchronously at the rejection choke point** (`OrderService.reject`), *not* via an `@ApplicationModuleListener` — a metrics-incrementing event consumer would double-count on at-least-once redelivery and would drag in the idempotent-consumer rule.
3. **Submit counter added** — spec §6 panel 1 references a "submit counter" that spec §5 never enumerates. We add a tiny `tradecore.orders.submitted` Counter so the throughput panel is real.
4. **Task split** — spec §8 task 3 (instrumentation) is split into Task 3 (order-side metrics, `orders` module) and Task 4 (registry-lag gauge, `config` module) because they live in different modules and a reviewer could accept one while rejecting the other. Six tasks total.

## File Structure

**Created:**
- `src/main/java/io/github/ajayaj724/tradecore/reconciliation/ReconciliationService.java` — the `@Service`: `reconcile()` computes drift + equity, publishes gauges; `@Scheduled`.
- `src/main/java/io/github/ajayaj724/tradecore/reconciliation/ReconciliationProperties.java` — `@ConfigurationProperties` record binding `tradecore.reconciliation.accounts` / `.symbols` (defaults: `[trader1, trader2]` / `[ACME]`).
- `src/main/java/io/github/ajayaj724/tradecore/reconciliation/ReconciliationConfig.java` — `@Configuration` enabling scheduling + the properties.
- `src/main/java/io/github/ajayaj724/tradecore/config/EventRegistryLagMetrics.java` — `@Component` gauge over incomplete `event_publication` rows.
- `infra/grafana/provisioning/dashboards/dashboards.yaml` — Grafana file-provider config.
- `infra/grafana/provisioning/dashboards/tradecore.json` — the six-panel dashboard.
- `docs/adr/0009-reconciliation-read-only-reporting-module.md` — boundary ADR.
- Tests: `src/test/java/io/github/ajayaj724/tradecore/risk/RiskSettledGettersIT.java`, `.../reconciliation/ReconciliationServiceIT.java`, `.../orders/OrderMetricsIT.java`, `.../config/EventRegistryLagMetricsIT.java`, `.../observability/GrafanaDashboardTest.java`.

**Modified:**
- `src/main/java/io/github/ajayaj724/tradecore/risk/RiskService.java` — add public `settledCash` / `settledHoldings` getters.
- `src/main/java/io/github/ajayaj724/tradecore/orders/OrderService.java` — inject `MeterRegistry`; submit Counter, rejection Counter, fill-latency Timer.
- `README.md` — Observability section + "What's not here yet" list.

---

### Task 1: `risk` settled-state read getters

**Files:**
- Modify: `src/main/java/io/github/ajayaj724/tradecore/risk/RiskService.java`
- Test: `src/test/java/io/github/ajayaj724/tradecore/risk/RiskSettledGettersIT.java`

**Interfaces:**
- Consumes: existing `RiskService(JdbcClient jdbc, Clock clock)`, tables `risk.settled_cash(account, amount)` and `risk.settled_holdings(account, symbol, qty)`.
- Produces: `public long settledCash(String account)` and `public long settledHoldings(String account, String symbol)` — both return `0L` when no row exists. Consumed by Task 2.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/github/ajayaj724/tradecore/risk/RiskSettledGettersIT.java`:

```java
package io.github.ajayaj724.tradecore.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class RiskSettledGettersIT {

    private final RiskService risk;

    @Autowired
    RiskSettledGettersIT(RiskService risk) {
        this.risk = risk;
    }

    @Test
    void exposesSeededSettledCash() {
        assertThat(risk.settledCash("trader1")).isEqualTo(100_000_000L);
    }

    @Test
    void exposesSeededSettledHoldings() {
        assertThat(risk.settledHoldings("trader1", "ACME")).isEqualTo(1000L);
    }

    @Test
    void returnsZeroForUnknownAccountOrSymbol() {
        assertThat(risk.settledCash("nobody")).isEqualTo(0L);
        assertThat(risk.settledHoldings("trader1", "NOSUCH")).isEqualTo(0L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=RiskSettledGettersIT`
Expected: FAIL — compilation error, `settledCash(String)` / `settledHoldings(String, String)` not defined on `RiskService`.

- [ ] **Step 3: Add the getters**

In `src/main/java/io/github/ajayaj724/tradecore/risk/RiskService.java`, add these two public methods (mirroring the existing `.optional()` read idiom, minus the `for update` lock). Ensure `import org.springframework.transaction.annotation.Transactional;` is present:

```java
@Transactional(readOnly = true)
public long settledCash(String account) {
    return jdbc.sql("select amount from risk.settled_cash where account = :a")
            .param("a", account)
            .query(Long.class)
            .optional()
            .orElse(0L);
}

@Transactional(readOnly = true)
public long settledHoldings(String account, String symbol) {
    return jdbc.sql("select qty from risk.settled_holdings where account = :a and symbol = :s")
            .param("a", account)
            .param("s", symbol)
            .query(Long.class)
            .optional()
            .orElse(0L);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=RiskSettledGettersIT`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

Run the `tradecore-quality-gate` (`scripts/gate.sh`) — must be green — then:

```bash
git add src/main/java/io/github/ajayaj724/tradecore/risk/RiskService.java \
        src/test/java/io/github/ajayaj724/tradecore/risk/RiskSettledGettersIT.java
git commit -m "feat: risk settled-state read getters for reconciliation"
```

---

### Task 2: `reconciliation` module — drift + equity gauges

**Files:**
- Create: `src/main/java/io/github/ajayaj724/tradecore/reconciliation/ReconciliationProperties.java`
- Create: `src/main/java/io/github/ajayaj724/tradecore/reconciliation/ReconciliationConfig.java`
- Create: `src/main/java/io/github/ajayaj724/tradecore/reconciliation/ReconciliationService.java`
- Create: `docs/adr/0009-reconciliation-read-only-reporting-module.md`
- Test: `src/test/java/io/github/ajayaj724/tradecore/reconciliation/ReconciliationServiceIT.java`

**Interfaces:**
- Consumes: `RiskService.settledCash/settledHoldings` (Task 1); `LedgerService.balanceOf(String)`; `PortfolioService.positionQty(String, String)`; `MarketDataService.lastPrice(String)` (throws on unknown symbol); `MeterRegistry`.
- Produces: gauge `tradecore.reconciliation.drift.pairs` (count of drifted `(account, symbol)` pairs; `0` = healthy) and per-account gauge `tradecore.account.equity{account=…}`; `public void reconcile()` — directly callable and `@Scheduled`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/github/ajayaj724/tradecore/reconciliation/ReconciliationServiceIT.java`:

```java
package io.github.ajayaj724.tradecore.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfig.class)
class ReconciliationServiceIT {

    private final ReconciliationService reconciliation;
    private final MeterRegistry registry;
    private final JdbcClient jdbc;

    @Autowired
    ReconciliationServiceIT(ReconciliationService reconciliation, MeterRegistry registry, JdbcClient jdbc) {
        this.reconciliation = reconciliation;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    private double driftPairs() {
        return registry.get("tradecore.reconciliation.drift.pairs").gauge().value();
    }

    @Test
    void consistentSystemReportsZeroDrift() {
        reconciliation.reconcile();
        assertThat(driftPairs()).isZero();
    }

    @Test
    void computesPerAccountEquity() {
        reconciliation.reconcile();
        // 100_000_000 cash + 1000 ACME * 10_000 last price = 110_000_000 paise
        double equity = registry.get("tradecore.account.equity").tag("account", "trader1").gauge().value();
        assertThat(equity).isEqualTo(110_000_000d);
    }

    @Test
    @Transactional // perturbation rolls back so other classes see pristine seed
    void forcedDivergenceRaisesDrift() {
        jdbc.sql("update risk.settled_cash set amount = amount + 1 where account = :a")
                .param("a", "trader1")
                .update();
        reconciliation.reconcile();
        assertThat(driftPairs()).isGreaterThanOrEqualTo(1d);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ReconciliationServiceIT`
Expected: FAIL — `ReconciliationService` does not exist (compilation error).

- [ ] **Step 3: Create the properties record**

`src/main/java/io/github/ajayaj724/tradecore/reconciliation/ReconciliationProperties.java`:

```java
package io.github.ajayaj724.tradecore.reconciliation;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradecore.reconciliation")
record ReconciliationProperties(List<String> accounts, List<String> symbols) {

    ReconciliationProperties {
        accounts = (accounts == null || accounts.isEmpty()) ? List.of("trader1", "trader2") : accounts;
        symbols = (symbols == null || symbols.isEmpty()) ? List.of("ACME") : symbols;
    }
}
```

- [ ] **Step 4: Create the config (scheduling + properties)**

`src/main/java/io/github/ajayaj724/tradecore/reconciliation/ReconciliationConfig.java`:

```java
package io.github.ajayaj724.tradecore.reconciliation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReconciliationProperties.class)
class ReconciliationConfig {}
```

- [ ] **Step 5: Create the service**

`src/main/java/io/github/ajayaj724/tradecore/reconciliation/ReconciliationService.java`:

```java
package io.github.ajayaj724.tradecore.reconciliation;

import io.github.ajayaj724.tradecore.ledger.LedgerService;
import io.github.ajayaj724.tradecore.marketdata.MarketDataService;
import io.github.ajayaj724.tradecore.portfolio.PortfolioService;
import io.github.ajayaj724.tradecore.risk.RiskService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Read-only reconciliation: proves the event-fed read-models (risk's settled projections) have not
 * drifted from their source of truth (ledger cash, portfolio positions). Pure fan-in — nothing
 * depends on this module. See ADR-0009 for the cross-module read-access rationale.
 */
@Service
public class ReconciliationService {

    private final RiskService risk;
    private final LedgerService ledger;
    private final PortfolioService portfolio;
    private final MarketDataService marketData;
    private final ReconciliationProperties props;

    private final AtomicInteger driftPairs = new AtomicInteger(0);
    private final Map<String, AtomicLong> equityByAccount = new HashMap<>();

    ReconciliationService(
            RiskService risk,
            LedgerService ledger,
            PortfolioService portfolio,
            MarketDataService marketData,
            ReconciliationProperties props,
            MeterRegistry registry) {
        this.risk = risk;
        this.ledger = ledger;
        this.portfolio = portfolio;
        this.marketData = marketData;
        this.props = props;

        Gauge.builder("tradecore.reconciliation.drift.pairs", driftPairs, AtomicInteger::doubleValue)
                .description("count of (account, symbol) pairs whose cash or holdings drift is non-zero; 0 = healthy")
                .register(registry);

        for (String account : props.accounts()) {
            AtomicLong holder = new AtomicLong(0);
            equityByAccount.put(account, holder);
            Gauge.builder("tradecore.account.equity", holder, AtomicLong::doubleValue)
                    .description("net asset value per account in paise: cash + sum(position * last price)")
                    .tag("account", account)
                    .register(registry);
        }
    }

    /** Recompute drift and equity for the configured universe and publish the gauges. */
    @Scheduled(
            initialDelayString = "${tradecore.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${tradecore.reconciliation.fixed-delay-ms:60000}")
    public void reconcile() {
        int drifted = 0;
        for (String account : props.accounts()) {
            long cashDrift = risk.settledCash(account) - ledger.balanceOf(account);
            long positionsValue = 0;
            for (String symbol : props.symbols()) {
                long qty = portfolio.positionQty(account, symbol);
                long holdingsDrift = risk.settledHoldings(account, symbol) - qty;
                if (cashDrift != 0 || holdingsDrift != 0) {
                    drifted++;
                }
                if (qty != 0) {
                    try {
                        positionsValue += qty * marketData.lastPrice(symbol);
                    } catch (RuntimeException noPrice) {
                        // no last price for this symbol yet — omit from equity, keep reconciling
                    }
                }
            }
            AtomicLong holder = equityByAccount.get(account);
            if (holder != null) {
                holder.set(ledger.balanceOf(account) + positionsValue);
            }
        }
        driftPairs.set(drifted);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -Dtest=ReconciliationServiceIT`
Expected: PASS (3 tests).

- [ ] **Step 7: Verify module boundaries stay green**

Run: `mvn test -Dtest=ModularityTests`
Expected: PASS — `reconciliation` is acyclic fan-in over public services.

- [ ] **Step 8: Write the boundary ADR**

Create `docs/adr/0009-reconciliation-read-only-reporting-module.md`:

```markdown
# 0009: Reconciliation as a read-only reporting module

- Status: accepted
- Date: 2026-07-08

## Context

The design spec makes `orders -> risk` the only synchronous cross-module call on the trading
**write** path. Phase 2C adds a `reconciliation` job that must compare risk's settled projections
against the ledger/portfolio source of truth — which means reading settled state across several
modules. Taken literally, the "only `orders -> risk` sync" rule would forbid this.

## Decision

Permit `reconciliation` to call the **public read APIs** of `ledger`, `portfolio`, `risk`, and
`marketdata` (`balanceOf`, `positionQty`, `settledCash`, `settledHoldings`, `lastPrice`). It reads
no other module's tables. The write-path rule governs the trading path; read-only reporting is a
distinct category. `reconciliation` is pure fan-in — nothing depends on it — so
`ApplicationModules.verify()` stays acyclic. The rejected alternative was every module
re-publishing snapshot events for a batch job, which is machinery the tracer does not need.

## Consequences

- `reconciliation` shows up as depending on four modules in the generated architecture docs; this is
  intended and bounded to read methods.
- Reconciliation detects drift from a lost/duplicated event; it does not remediate (operator work).
- If the write-path sync rule is ever tightened to "no cross-module sync at all," this ADR is the
  documented exception for reporting reads.
```

- [ ] **Step 9: Commit**

Run the `tradecore-quality-gate` (`scripts/gate.sh`) — must be green — then:

```bash
git add src/main/java/io/github/ajayaj724/tradecore/reconciliation/ \
        src/test/java/io/github/ajayaj724/tradecore/reconciliation/ \
        docs/adr/0009-reconciliation-read-only-reporting-module.md
git commit -m "feat: reconciliation module — drift + equity gauges over module read APIs"
```

---

### Task 3: Order-side instrumentation (submit counter, rejection counter, fill-latency timer)

**Files:**
- Modify: `src/main/java/io/github/ajayaj724/tradecore/orders/OrderService.java`
- Test: `src/test/java/io/github/ajayaj724/tradecore/orders/OrderMetricsIT.java`

**Interfaces:**
- Consumes: existing `OrderService` collaborators; `MeterRegistry` (new constructor param); `orders.audit` `SUBMITTED` row (`occurred_at`, `Clock`-sourced); `TradeExecuted.occurredAt()`.
- Produces: Counter `tradecore.orders.submitted`; Counter `tradecore.risk.rejections{reason=…}`; Timer `tradecore.order.fill.latency` (percentile histogram).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/github/ajayaj724/tradecore/orders/OrderMetricsIT.java`. This drives a real submit (rejected, since a BUY far exceeds seeded cash) and asserts the counters; the fill-latency timer is asserted via the existing end-to-end fill path helpers. Uses Awaitility for async fill propagation.

```java
package io.github.ajayaj724.tradecore.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.Side;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class OrderMetricsIT {

    private final OrderService orders;
    private final MeterRegistry registry;

    @Autowired
    OrderMetricsIT(OrderService orders, MeterRegistry registry) {
        this.orders = orders;
        this.registry = registry;
    }

    @Test
    void submitIncrementsSubmittedCounter() {
        double before = registry.counter("tradecore.orders.submitted").count();
        orders.submit("trader1", "trader1", new SubmitOrderCommand("k-" + java.util.UUID.randomUUID(), "ACME", Side.BUY, 100L, 1L));
        assertThat(registry.counter("tradecore.orders.submitted").count()).isEqualTo(before + 1);
    }

    @Test
    void riskRejectionIncrementsRejectionCounterTaggedByReason() {
        // BUY 1_000_000 shares * 100 paise = 100_000_000_000 >> seeded 100_000_000 cash -> insufficient cash
        orders.submit(
                "trader1", "trader1", new SubmitOrderCommand("k-" + java.util.UUID.randomUUID(), "ACME", Side.BUY, 100L, 1_000_000L));
        assertThat(registry.get("tradecore.risk.rejections").tag("reason", "insufficient cash").counter().count())
                .isGreaterThanOrEqualTo(1d);
    }
}
```

> `SubmitOrderCommand` is `record SubmitOrderCommand(String idempotencyKey, String symbol, Side side, long price, long quantity)` and `OrderService.submit(String account, String principal, SubmitOrderCommand cmd)` is package-private — this IT lives in the same `orders` package, so it can autowire `OrderService` and call `submit(...)` directly (as `OrderFillListenerIT` does).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OrderMetricsIT`
Expected: FAIL — `tradecore.orders.submitted` counter count is `0`/absent; rejection meter not found.

- [ ] **Step 3: Inject `MeterRegistry` and build the meters**

In `src/main/java/io/github/ajayaj724/tradecore/orders/OrderService.java`, add imports:

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.OffsetDateTime;
```

Add fields and extend the constructor (add the `MeterRegistry registry` parameter last):

```java
    private final MeterRegistry registry;
    private final Counter submitted;
    private final Timer fillLatency;
```

In the constructor body add:

```java
        this.registry = registry;
        this.submitted = Counter.builder("tradecore.orders.submitted")
                .description("orders submitted")
                .register(registry);
        this.fillLatency = Timer.builder("tradecore.order.fill.latency")
                .description("submit to filled duration")
                .publishPercentileHistogram()
                .register(registry);
```

- [ ] **Step 4: Increment the submit counter**

In `submit(...)`, immediately after `record(created, "SUBMITTED", principal);`:

```java
        submitted.increment();
```

- [ ] **Step 5: Increment the rejection counter (tagged by reason)**

In `reject(...)`, after `events.publishEvent(new OrderRejected(...));` and before `return rejected;`:

```java
        registry.counter("tradecore.risk.rejections", "reason", reason).increment();
```

- [ ] **Step 6: Record fill latency when an order reaches FILLED**

Change `applyTrade` to pass the fill instant into `applyToOrder`:

```java
    @Transactional
    void applyTrade(TradeExecuted trade) {
        if (tradeAlreadyApplied(trade.eventId())) {
            return;
        }
        applyToOrder(trade.buyOrderId(), trade.quantity(), trade.occurredAt());
        applyToOrder(trade.sellOrderId(), trade.quantity(), trade.occurredAt());
        jdbc.sql("insert into orders.applied_trade (event_id, order_id, applied_at) values (:e, :o, :t)")
                .param("e", trade.eventId())
                .param("o", trade.buyOrderId())
                .param("t", OffsetDateTime.now(clock))
                .update();
    }
```

Change `applyToOrder` to accept the instant and record the timer on the FILLED transition:

```java
    private void applyToOrder(long orderId, long quantity, java.time.Instant filledAt) {
        Order order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        Order filled = orders.save(order.withFill(quantity));
        record(filled, filled.status().name(), "system");
        if (filled.status() == OrderStatus.FILLED) {
            recordFillLatency(orderId, filledAt);
        }
    }

    private void recordFillLatency(long orderId, java.time.Instant filledAt) {
        OffsetDateTime submittedAt = jdbc.sql(
                        "select occurred_at from orders.audit where order_id = :o and action = 'SUBMITTED' order by id limit 1")
                .param("o", orderId)
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);
        if (submittedAt != null) {
            fillLatency.record(Duration.between(submittedAt.toInstant(), filledAt));
        }
    }
```

> The `applied_trade` idempotency guard (`tradeAlreadyApplied`) ensures a redelivered `TradeExecuted` short-circuits before reaching `applyToOrder`, so the timer records at most once per fill.

- [ ] **Step 7: Run test to verify it passes**

Run: `mvn test -Dtest=OrderMetricsIT`
Expected: PASS (2 tests). Also run the existing order ITs to confirm no regression from the constructor/signature change:
Run: `mvn test -Dtest='OrderFillEndToEndIT,OrderSubmissionIT,OrderFillListenerIT'`
Expected: PASS.

- [ ] **Step 8: Commit**

Run the `tradecore-quality-gate` (`scripts/gate.sh`) — must be green — then:

```bash
git add src/main/java/io/github/ajayaj724/tradecore/orders/OrderService.java \
        src/test/java/io/github/ajayaj724/tradecore/orders/OrderMetricsIT.java
git commit -m "feat: order metrics — submit/rejection counters and fill-latency timer"
```

---

### Task 4: Event-registry-lag gauge

**Files:**
- Create: `src/main/java/io/github/ajayaj724/tradecore/config/EventRegistryLagMetrics.java`
- Test: `src/test/java/io/github/ajayaj724/tradecore/config/EventRegistryLagMetricsIT.java`

**Interfaces:**
- Consumes: `JdbcClient`; `event_publication` table (`completion_date`); `MeterRegistry`.
- Produces: gauge `tradecore.events.registry.lag` = count of `event_publication` rows with `completion_date IS NULL`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/github/ajayaj724/tradecore/config/EventRegistryLagMetricsIT.java`:

```java
package io.github.ajayaj724.tradecore.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfig.class)
class EventRegistryLagMetricsIT {

    private final MeterRegistry registry;
    private final JdbcClient jdbc;

    @Autowired
    EventRegistryLagMetricsIT(MeterRegistry registry, JdbcClient jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @Test
    void gaugeReflectsIncompletePublicationCount() {
        double gauge = registry.get("tradecore.events.registry.lag").gauge().value();
        Long direct = jdbc.sql("select count(*) from event_publication where completion_date is null")
                .query(Long.class)
                .single();
        assertThat(gauge).isEqualTo(direct.doubleValue());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EventRegistryLagMetricsIT`
Expected: FAIL — meter `tradecore.events.registry.lag` not found.

- [ ] **Step 3: Create the gauge component**

`src/main/java/io/github/ajayaj724/tradecore/config/EventRegistryLagMetrics.java`:

```java
package io.github.ajayaj724.tradecore.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Publishes the event-publication backlog — Spring Modulith rows whose {@code completion_date} is
 * still null — as a gauge. A rising value means consumers are lagging or failing.
 */
@Component
class EventRegistryLagMetrics {

    private final JdbcClient jdbc;

    EventRegistryLagMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("tradecore.events.registry.lag", this, EventRegistryLagMetrics::incompletePublications)
                .description("event_publication rows not yet completed (consumer backlog)")
                .register(registry);
    }

    double incompletePublications() {
        Long count = jdbc.sql("select count(*) from event_publication where completion_date is null")
                .query(Long.class)
                .single();
        return count == null ? 0d : count.doubleValue();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=EventRegistryLagMetricsIT`
Expected: PASS.

- [ ] **Step 5: Commit**

Run the `tradecore-quality-gate` (`scripts/gate.sh`) — must be green — then:

```bash
git add src/main/java/io/github/ajayaj724/tradecore/config/EventRegistryLagMetrics.java \
        src/test/java/io/github/ajayaj724/tradecore/config/EventRegistryLagMetricsIT.java
git commit -m "feat: event-registry-lag gauge over incomplete publications"
```

---

### Task 5: Grafana dashboards (provisioned)

**Files:**
- Create: `infra/grafana/provisioning/dashboards/dashboards.yaml`
- Create: `infra/grafana/provisioning/dashboards/tradecore.json`
- Test: `src/test/java/io/github/ajayaj724/tradecore/observability/GrafanaDashboardTest.java`

**Interfaces:**
- Consumes: Prometheus metric names produced by Tasks 2–4 (`tradecore_reconciliation_drift_pairs`, `tradecore_order_fill_latency_seconds_bucket`, `tradecore_orders_submitted_total`, `tradecore_risk_rejections_total`, `tradecore_events_registry_lag`).
- Produces: a provisioned Grafana board; a JSON-validity test binding the dashboard to the metric names.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/github/ajayaj724/tradecore/observability/GrafanaDashboardTest.java` (plain unit test — no Spring, no Testcontainers). Reads the dashboard from the project-root-relative infra path:

```java
package io.github.ajayaj724.tradecore.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GrafanaDashboardTest {

    private static final Path DASHBOARD = Path.of("infra/grafana/provisioning/dashboards/tradecore.json");
    private static final Path PROVIDER = Path.of("infra/grafana/provisioning/dashboards/dashboards.yaml");

    @Test
    void dashboardIsWellFormedAndReferencesKeyMetrics() throws Exception {
        assertThat(Files.exists(PROVIDER)).isTrue();

        JsonNode root = new ObjectMapper().readTree(Files.readString(DASHBOARD));
        JsonNode panels = root.get("panels");
        assertThat(panels).isNotNull();
        assertThat(panels.isArray()).isTrue();
        assertThat(panels.size()).isGreaterThanOrEqualTo(6);

        String json = root.toString();
        assertThat(json).contains("tradecore_reconciliation_drift_pairs");
        assertThat(json).contains("tradecore_order_fill_latency_seconds_bucket");
        assertThat(json).contains("tradecore_orders_submitted_total");
        assertThat(json).contains("tradecore_risk_rejections_total");
        assertThat(json).contains("tradecore_events_registry_lag");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=GrafanaDashboardTest`
Expected: FAIL — `NoSuchFileException` (dashboard JSON absent).

- [ ] **Step 3: Create the provider config**

`infra/grafana/provisioning/dashboards/dashboards.yaml`:

```yaml
apiVersion: 1

providers:
  - name: tradecore
    orgId: 1
    folder: ''
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    allowUiUpdates: true
    options:
      path: /etc/grafana/provisioning/dashboards
      foldersFromFilesStructure: false
```

- [ ] **Step 4: Create the dashboard JSON**

`infra/grafana/provisioning/dashboards/tradecore.json` — six panels. Each `targets[].expr` is real PromQL over the metrics from Tasks 2–4:

```json
{
  "uid": "tradecore-overview",
  "title": "tradecore — OMS overview",
  "schemaVersion": 39,
  "editable": true,
  "time": { "from": "now-1h", "to": "now" },
  "refresh": "30s",
  "panels": [
    {
      "id": 1,
      "title": "Order throughput (orders/sec)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "targets": [
        { "refId": "A", "expr": "sum(rate(tradecore_orders_submitted_total[1m]))", "legendFormat": "submitted" }
      ]
    },
    {
      "id": 2,
      "title": "Fill latency (p50 / p99)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
      "targets": [
        { "refId": "A", "expr": "histogram_quantile(0.50, sum(rate(tradecore_order_fill_latency_seconds_bucket[5m])) by (le))", "legendFormat": "p50" },
        { "refId": "B", "expr": "histogram_quantile(0.99, sum(rate(tradecore_order_fill_latency_seconds_bucket[5m])) by (le))", "legendFormat": "p99" }
      ]
    },
    {
      "id": 3,
      "title": "Risk rejection rate (by reason)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
      "targets": [
        { "refId": "A", "expr": "sum(rate(tradecore_risk_rejections_total[5m])) by (reason)", "legendFormat": "{{reason}}" }
      ]
    },
    {
      "id": 4,
      "title": "Event-registry lag (incomplete publications)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
      "targets": [
        { "refId": "A", "expr": "tradecore_events_registry_lag", "legendFormat": "incomplete" }
      ]
    },
    {
      "id": 5,
      "title": "Reconciliation drift (should flatline at 0)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 16 },
      "targets": [
        { "refId": "A", "expr": "tradecore_reconciliation_drift_pairs", "legendFormat": "drifted pairs" }
      ]
    },
    {
      "id": 6,
      "title": "JVM / virtual threads",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 16 },
      "targets": [
        { "refId": "A", "expr": "sum(jvm_memory_used_bytes)", "legendFormat": "heap+nonheap used" },
        { "refId": "B", "expr": "jvm_threads_live_threads", "legendFormat": "live threads" }
      ]
    }
  ]
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=GrafanaDashboardTest`
Expected: PASS.

- [ ] **Step 6: Sanity-check provisioning loads (optional, requires Docker)**

Run: `scripts/up.sh` then open `http://localhost:3000` → Dashboards → "tradecore — OMS overview" renders (panels may show "No data" until traffic flows). This is a manual smoke check, not part of the gate.

- [ ] **Step 7: Commit**

Run the `tradecore-quality-gate` (`scripts/gate.sh`) — must be green — then:

```bash
git add infra/grafana/provisioning/dashboards/ \
        src/test/java/io/github/ajayaj724/tradecore/observability/GrafanaDashboardTest.java
git commit -m "feat: provisioned Grafana dashboard for OMS metrics and reconciliation drift"
```

---

### Task 6: Closeout — README + final gate + Phase 2 complete

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: everything built in Tasks 1–5.
- Produces: updated docs; a fully green `mvn verify`; Phase 2 marked complete.

- [ ] **Step 1: Update the Observability section**

In `README.md`, in the `## Observability` section, replace the note that dashboards "are not built yet … Tracked for Phase 2" with a statement that dashboards are provisioned. Add a short line naming the reconciliation drift gauge:

```markdown
Grafana (`:3000`, anonymous viewer) auto-provisions the **tradecore — OMS overview** board from
`infra/grafana/provisioning/dashboards/`: order throughput, fill-latency p50/p99, risk-rejection
rate, event-registry lag, reconciliation drift (`tradecore_reconciliation_drift_pairs`, flat at 0
when consistent), and JVM/virtual-thread health.
```

- [ ] **Step 2: Update the "What's not here yet" list**

In `README.md`, in `## What's not here yet`, remove (or amend) the lines that list end-of-day reconciliation and Grafana dashboards as Phase 2 not-yet-built, since both now exist. Leave genuinely-out-of-scope items (alerting/paging, historical drift storage, automatic remediation, full account/symbol enumeration) — add them if not already listed:

```markdown
- Reconciliation reports the latest run only (no historical drift storage) and does not remediate
  drift or page on it — alerting on the emitted gauges is a deploy-time concern.
```

- [ ] **Step 3: Run the full gate**

Run the `tradecore-quality-gate` (`scripts/gate.sh`).
Expected: green — format check, Error Prone, Checkstyle, ArchUnit (`noSystemClock`), all unit + Testcontainers ITs, `ApplicationModules.verify()`, JaCoCo ≥ 80%.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: README observability + reconciliation; Phase 2 complete"
```

- [ ] **Step 5: Finish the branch**

Phase 2 is complete — the money story (cash, positions, P&L, reconciliation, dashboards) is done.
Use `superpowers:finishing-a-development-branch` to choose merge/PR/cleanup for `feat/phase2c-reconciliation`.

---

## Self-Review

**Spec coverage** (against `2026-07-08-phase2c-reconciliation-design.md`):
- §1.1 reconciliation module → Task 2. Instrumentation (fill Timer, rejection Counter, registry-lag gauge) → Tasks 3–4. Grafana dashboards → Task 5. New risk getters → Task 1. ✅
- §1.2 out-of-scope (alerting, historical storage, remediation, full enumeration) → honored; documented in Task 6. ✅
- §2 decisions: read-only module (Task 2 + ADR-0009), `@Scheduled` cadence (Task 2), drift = count of drifted pairs (Task 2 + test), configured universe (`ReconciliationProperties`). ✅
- §3 cash drift / holdings drift / equity formulas → `reconcile()` in Task 2; gauge names `tradecore.reconciliation.drift.pairs`, `tradecore.account.equity` match. ✅
- §4 boundary rationale → ADR-0009 (Task 2, step 8); getters (Task 1). ✅
- §5 metrics: fill latency (Task 3), rejection rate tagged by reason (Task 3), event-registry lag (Task 4), drift (Task 2). Clock-based durations, no zero-arg `now()`. ✅ Added submit counter (documented gap-fill).
- §6 six panels → `tradecore.json` (Task 5); panel 1's "submit counter" now exists. ✅
- §7 tests: consistent→0 (Task 2), forced divergence→>0 (Task 2), equity (Task 2), metric presence (Tasks 3–4), `verify()` (Task 2 step 7), dashboard JSON validates (Task 5). JaCoCo ≥ 80% enforced by the gate every commit. ✅
- §8 task order preserved (instrumentation split 3→3+4, documented). §9 ADR-0009 written. §10 DoD covered by Task 6. ✅

**Placeholder scan:** No "TBD"/"handle appropriately"/"similar to Task N". One flagged implementer check remains (verify `SubmitOrderCommand` component order in Task 3 step 1) — this is a real read-before-run instruction, not a code placeholder, because the command's exact constructor shape was not captured during planning.

**Type consistency:** `settledCash(String)`/`settledHoldings(String,String)` return `long`, consumed as `long` in `reconcile()`. Gauge names identical between service code, tests, and dashboard PromQL (dotted in Java ↔ underscored in Prometheus): `tradecore.reconciliation.drift.pairs` ↔ `tradecore_reconciliation_drift_pairs`, `tradecore.order.fill.latency` ↔ `tradecore_order_fill_latency_seconds_bucket`, `tradecore.orders.submitted` ↔ `tradecore_orders_submitted_total`, `tradecore.risk.rejections` ↔ `tradecore_risk_rejections_total`, `tradecore.events.registry.lag` ↔ `tradecore_events_registry_lag`. All identifiers/symbols are `String`; all money/qty `long`. ✅
