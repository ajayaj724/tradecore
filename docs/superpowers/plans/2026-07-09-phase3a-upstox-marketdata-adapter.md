# Phase 3A — Upstox Market-Data Adapter behind Resilience4j Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Feed live external market-data (Upstox LTP, stubbed via WireMock against the real contract) into the existing `marketdata` read-model through a scheduled, Resilience4j-guarded HTTP adapter that degrades safely when the upstream is slow or down.

**Architecture:** A new inbound adapter inside the `marketdata` module. A `@Scheduled` poller calls an `UpstoxClient` (Spring `RestClient`) per configured symbol; the call is annotation-decorated (`@Retry` + `@CircuitBreaker` + `fallbackMethod`) with an HTTP read-timeout for the slow-call bound. On success it applies the price via `MarketDataService.applyExternalPrice`, reusing the existing `last_price` upsert + `PriceUpdated` publish path; on failure/open-circuit it retains the last-known price and advances a staleness gauge.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring `RestClient`, Resilience4j 2.4.0 (`resilience4j-spring-boot4`), AspectJ weaver, Jackson 3 (`tools.jackson`), Micrometer/Prometheus, WireMock 3.13.2, Testcontainers, JUnit 5 + AssertJ + Awaitility.

## Global Constraints

Copied from the spec (`2026-07-09-phase3a-upstox-marketdata-adapter-design.md`) and CLAUDE.md; every task implicitly includes these.

- **Money is `long` paise.** The Upstox `last_price` is a JSON decimal — parse it as `BigDecimal` **at the adapter boundary only** and convert to `long` paise immediately: `price.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_EVEN).longValueExact()`. No `double`/`float`/`BigDecimal` past the adapter boundary; the `marketdata.last_price` column and `PriceUpdated.price()` stay `long`/`BIGINT`.
- **Deterministic time.** Inject `java.time.Clock`; feed staleness = `Duration.between(lastSuccess, clock.instant())`. No zero-arg `Instant.now()`/`System.currentTimeMillis()` (ArchUnit `noSystemClock`).
- **Module boundary.** The adapter lives inside `marketdata`; it publishes only the existing `PriceUpdated` event and adds no new cross-module *synchronous* call. `ApplicationModules.verify()` must stay green.
- **Spike-verified dependencies (do not re-litigate):** `io.github.resilience4j:resilience4j-spring-boot4:2.4.0` (annotation-driven), `org.aspectj:aspectjweaver` (BOM-managed, no explicit version — REQUIRED so the `@Aspect` weaves; `spring-boot-starter-aop` has no 4.x GA), `org.wiremock:wiremock:3.13.2` (test scope). No version pinned that the Boot BOM manages.
- **Resilience4j is annotation-driven; sync `RestClient` → read-timeout, NOT `@TimeLimiter`.** `@TimeLimiter` only applies to `CompletableFuture`-returning methods; for the synchronous call the read-timeout on the `RestClient`'s request factory is what abandons a slow response (→ `SocketTimeoutException` → `@Retry` → `@CircuitBreaker`). Thresholds via `resilience4j.circuitbreaker/retry.instances.upstox.*` properties.
- **Jackson 3.** Parse with the Boot-auto-configured mapper; DTO field aliases use `@JsonProperty` which **stays** at `com.fasterxml.jackson.annotation.JsonProperty` (the deliberate Jackson-3 exception).
- **NullAway/ErrorProne clean.** Nullable references annotated `org.jspecify.annotations.@Nullable`; no null leaks (NullAway runs in the compiler for `io.github.ajayaj724.tradecore`).
- **Every commit runs the full gate green** (`mvn spotless:apply` + `mvn verify`: format, Error Prone/NullAway, Checkstyle, PMD, ArchUnit, all Testcontainers ITs, `ApplicationModules.verify()`, 80% JaCoCo). No skip flags. **Working-tree caveat:** an unrelated `config/SecurityConfig.java` edit is present; `spotless:apply` would reformat it — `git stash push src/main/java/io/github/ajayaj724/tradecore/config/SecurityConfig.java` before the gate and `git stash pop` after; never stage it. Conventional commits ending with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **Metric names** are stable dotted identifiers (Micrometer → Prometheus underscores). Explicit versions verified against the official source at commit time.

## Key facts (verified during planning)

- **Upstox V3 LTP contract:** `GET {base}/v3/market-quote/ltp?instrument_key=<key>` (comma-separated, `|` separator, e.g. `NSE_EQ|INE009A01021`); headers `Accept: application/json`, `Authorization: Bearer <token>`. Response: `{"status":"success","data":{"<respKey>":{"last_price":303.9,"instrument_token":"...","ltq":..,"volume":..,"cp":..}}}`. **Quirk:** the response `data` map key (`:` separator) is NOT the request key (`|` separator) — for a per-symbol poll the map holds exactly one entry, so read `data.values()` (the single `Quote`), don't look up by request key.
- **`MarketDataService.onTrade` (marketdata/MarketDataService.java:28-42)** upserts `last_price` (`on conflict (symbol) do update`) and publishes `PriceUpdated` — the exact path `applyExternalPrice` mirrors; constructor is `(JdbcClient jdbc, ApplicationEventPublisher events, Clock clock)`.
- Scheduling is already enabled app-wide (`reconciliation/ReconciliationConfig` `@EnableScheduling`). `@ConfigurationProperties` record pattern exists (`ReconciliationProperties`). Grafana board: `infra/grafana/provisioning/dashboards/tradecore.json`; validation test: `src/test/java/io/github/ajayaj724/tradecore/observability/GrafanaDashboardTest.java`. Next ADR: **0010**.

## File Structure

**Created (main, in `marketdata/`):** `UpstoxProperties.java` (`@ConfigurationProperties` record), `UpstoxClientConfig.java` (`@Configuration`: `RestClient` bean with base URL + read timeout, `@EnableConfigurationProperties`), `UpstoxLtpResponse.java` (Jackson DTO), `UpstoxClient.java` (`RestClient` call + resilience annotations + DTO→paise + fallback), `UpstoxPriceFeed.java` (`@Scheduled` poller, holds last-success `Instant`).
**Modified:** `marketdata/MarketDataService.java` (add `applyExternalPrice`), `pom.xml`, `src/main/resources/application.yaml` (`tradecore.upstox.*`, `resilience4j.*`, poll interval, read timeout), `infra/grafana/provisioning/dashboards/tradecore.json` (+2 panels), `src/test/.../observability/GrafanaDashboardTest.java`, `README.md`, `docs/adr/0010-*.md`.
**Tests (in `marketdata/`):** `UpstoxClientConversionTest.java` (unit), `UpstoxClientIT.java` (WireMock happy), `UpstoxResilienceIT.java` (fault injection), `UpstoxPriceFeedIT.java` (feed propagation + fallback), `FeedStalenessIT.java`.

---

### Task 1: `UpstoxClient` — fetch + DTO→paise (happy path)

**Files:**
- Modify: `pom.xml` (add WireMock test dep)
- Create: `marketdata/UpstoxProperties.java`, `marketdata/UpstoxClientConfig.java`, `marketdata/UpstoxLtpResponse.java`, `marketdata/UpstoxClient.java`
- Test: `marketdata/UpstoxClientConversionTest.java`, `marketdata/UpstoxClientIT.java`

**Interfaces:**
- Produces: `UpstoxClient.ltp(String symbol) -> long` (paise). Consumed by Task 3's feed. `UpstoxProperties` (`base-url`, `access-token`, `instrument-keys` map). Consumed by later tasks.

- [ ] **Step 1: Add WireMock (test scope)** to `pom.xml` in the `<!-- test -->` block:
```xml
    <dependency><groupId>org.wiremock</groupId><artifactId>wiremock</artifactId><version>3.13.2</version><scope>test</scope></dependency>
```

- [ ] **Step 2: Write the failing conversion unit test** `src/test/java/io/github/ajayaj724/tradecore/marketdata/UpstoxClientConversionTest.java`:
```java
package io.github.ajayaj724.tradecore.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UpstoxClientConversionTest {

    @Test
    void convertsRupeeDecimalToPaiseHalfEven() {
        assertThat(UpstoxClient.toPaise(new BigDecimal("303.9"))).isEqualTo(30390L);
        assertThat(UpstoxClient.toPaise(new BigDecimal("100.00"))).isEqualTo(10000L);
        assertThat(UpstoxClient.toPaise(new BigDecimal("0.005"))).isEqualTo(0L); // half-even -> 0
        assertThat(UpstoxClient.toPaise(new BigDecimal("0.015"))).isEqualTo(2L); // half-even -> 2
    }
}
```

- [ ] **Step 3: Run it — expect FAIL** (`UpstoxClient` undefined): `mvn test -Dtest=UpstoxClientConversionTest` → compilation error.

- [ ] **Step 4: Create the DTO** `marketdata/UpstoxLtpResponse.java`:
```java
package io.github.ajayaj724.tradecore.marketdata;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Map;

/** Upstox V3 LTP response envelope. The {@code data} map is keyed by Upstox's response key (":" form). */
record UpstoxLtpResponse(String status, Map<String, Quote> data) {
    record Quote(@JsonProperty("last_price") BigDecimal lastPrice, @JsonProperty("instrument_token") String instrumentToken) {}
}
```

- [ ] **Step 5: Create the properties** `marketdata/UpstoxProperties.java`:
```java
package io.github.ajayaj724.tradecore.marketdata;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradecore.upstox")
record UpstoxProperties(String baseUrl, String accessToken, Map<String, String> instrumentKeys) {

    UpstoxProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.upstox.com" : baseUrl;
        accessToken = accessToken == null ? "" : accessToken;
        instrumentKeys = (instrumentKeys == null || instrumentKeys.isEmpty())
                ? Map.of("ACME", "NSE_EQ|ACME", "INFY", "NSE_EQ|INE009A01021")
                : instrumentKeys;
    }
}
```

- [ ] **Step 6: Create the RestClient config** `marketdata/UpstoxClientConfig.java`. NOTE for the implementer: the read-timeout request-factory API is a Boot-4.1 surface — confirm `ClientHttpRequestFactorySettings`/`ClientHttpRequestFactoryBuilder` signatures against the 4.1 jars (invoke debugging-spring-boot-4) before finalizing; the read timeout is load-bearing for the Task 2 slow-call test.
```java
package io.github.ajayaj724.tradecore.marketdata;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(UpstoxProperties.class)
class UpstoxClientConfig {

    @Bean
    RestClient upstoxRestClient(UpstoxProperties props) {
        var settings = ClientHttpRequestFactorySettings.defaults().withReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Authorization", "Bearer " + props.accessToken())
                .build();
    }
}
```

- [ ] **Step 7: Create the client (no resilience yet)** `marketdata/UpstoxClient.java`:
```java
package io.github.ajayaj724.tradecore.marketdata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Fetches last-traded price (paise) for a symbol from the Upstox V3 LTP endpoint. */
@Component
class UpstoxClient {

    private final RestClient rest;
    private final UpstoxProperties props;

    UpstoxClient(RestClient upstoxRestClient, UpstoxProperties props) {
        this.rest = upstoxRestClient;
        this.props = props;
    }

    long ltp(String symbol) {
        String key = props.instrumentKeys().getOrDefault(symbol, symbol);
        UpstoxLtpResponse body = rest.get()
                .uri(uri -> uri.path("/v3/market-quote/ltp").queryParam("instrument_key", key).build())
                .retrieve()
                .body(UpstoxLtpResponse.class);
        if (body == null || body.data() == null || body.data().isEmpty()) {
            throw new NoSuchElementException("no LTP for " + symbol);
        }
        // per-symbol poll -> exactly one entry; response key (":" form) != request key ("|" form)
        return toPaise(body.data().values().iterator().next().lastPrice());
    }

    static long toPaise(BigDecimal rupees) {
        return rupees.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }
}
```

- [ ] **Step 8: Run conversion test — expect PASS:** `mvn test -Dtest=UpstoxClientConversionTest`.

- [ ] **Step 9: Write the WireMock happy-path IT** `marketdata/UpstoxClientIT.java`:
```java
package io.github.ajayaj724.tradecore.marketdata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@Import(TestcontainersConfig.class)
class UpstoxClientIT {

    static final WireMockServer WM = new WireMockServer(0); // random port

    @BeforeAll
    static void start() {
        WM.start();
    }

    @AfterAll
    static void stop() {
        WM.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tradecore.upstox.base-url", () -> "http://localhost:" + WM.port());
    }

    private final UpstoxClient client;

    @Autowired
    UpstoxClientIT(UpstoxClient client) {
        this.client = client;
    }

    @BeforeEach
    void reset() {
        WM.resetAll();
    }

    @Test
    void fetchesAndConvertsLtpToPaise() {
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\",\"data\":{\"NSE_EQ:ACME\":{\"last_price\":100.00,\"instrument_token\":\"NSE_EQ|ACME\"}}}")));

        assertThat(client.ltp("ACME")).isEqualTo(10000L);
    }
}
```

- [ ] **Step 10: Run it — expect PASS:** `mvn test -Dtest=UpstoxClientIT` (Testcontainers + WireMock). Then run `mvn test -Dtest=ModularityTests` — `verify()` still green.

- [ ] **Step 11: Commit** (stash SecurityConfig, run `scripts/gate.sh` green, restore):
```bash
git add pom.xml src/main/java/io/github/ajayaj724/tradecore/marketdata/Upstox*.java \
        src/test/java/io/github/ajayaj724/tradecore/marketdata/UpstoxClient*.java
git commit -m "feat: Upstox LTP client — RestClient fetch + decimal→paise conversion"
```

---

### Task 2: Resilience4j decoration + fallback

**Files:**
- Modify: `pom.xml` (resilience4j + aspectjweaver), `src/main/resources/application.yaml` (resilience4j + read-timeout config), `marketdata/UpstoxClient.java` (annotations + fallback)
- Create: `docs/adr/0010-resilient-external-market-data-adapter.md`
- Test: `marketdata/UpstoxResilienceIT.java`

**Interfaces:**
- Consumes: `UpstoxClient.ltp` (Task 1). Produces: a resilient `ltp` that returns a sentinel from its fallback when the circuit is open / the call fails; the `"upstox"` CircuitBreaker registered in the Resilience4j registry.

- [ ] **Step 1: Add deps** to `pom.xml` — after the actuator dependency (main) and nothing else changes:
```xml
    <dependency><groupId>io.github.resilience4j</groupId><artifactId>resilience4j-spring-boot4</artifactId><version>2.4.0</version></dependency>
    <dependency><groupId>org.aspectj</groupId><artifactId>aspectjweaver</artifactId></dependency>
```

- [ ] **Step 2: Configure thresholds** in `src/main/resources/application.yaml` (and mirror the low-latency read-timeout intent — the RestClient factory timeout from Task 1 stays 2s):
```yaml
resilience4j:
  circuitbreaker:
    instances:
      upstox:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 5
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 2
  retry:
    instances:
      upstox:
        max-attempts: 3
        wait-duration: 200ms
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException
          - org.springframework.web.client.HttpServerErrorException
```

- [ ] **Step 3: Write the failing resilience IT** `marketdata/UpstoxResilienceIT.java`. Mirror `UpstoxClientIT`'s WireMock + `@DynamicPropertySource` setup, and additionally, in `@DynamicPropertySource`, set `resilience4j.circuitbreaker.instances.upstox.wait-duration-in-open-state` → `"1s"` and `...permitted-number-of-calls-in-half-open-state` → `"1"` (so recovery is testable without a long wait). Autowire `UpstoxClient` + `io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry registry`. In `@BeforeEach`: `WM.resetAll();` **and** `registry.circuitBreaker("upstox").reset();` — the breaker is a context singleton, so the reset makes these tests order-independent (without it, an open circuit from one test corrupts the next). Assert three behaviors (import `io.github.resilience4j.circuitbreaker.CircuitBreaker`, `static org.awaitility.Awaitility.await`; `UNAVAILABLE` is Step 4's sentinel):
```java
    @Test
    void slowResponseTripsReadTimeoutAndFallsBack() {
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp"))
                .willReturn(aResponse().withFixedDelay(5000) // > 2s read timeout
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\",\"data\":{}}")));
        assertThat(client.ltp("ACME")).isEqualTo(UpstoxClient.UNAVAILABLE); // fallback sentinel
    }

    @Test
    void repeatedServerErrorsOpenTheCircuit() {
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp")).willReturn(aResponse().withStatus(500)));
        for (int i = 0; i < 5; i++) {
            assertThat(client.ltp("ACME")).isEqualTo(UpstoxClient.UNAVAILABLE);
        }
        assertThat(registry.circuitBreaker("upstox").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void circuitRecoversToClosedWhenUpstreamHealthyAgain() {
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp")).willReturn(aResponse().withStatus(500)));
        for (int i = 0; i < 5; i++) {
            client.ltp("ACME");
        }
        assertThat(registry.circuitBreaker("upstox").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        WM.resetAll();
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\",\"data\":{\"NSE_EQ:ACME\":{\"last_price\":100.00,\"instrument_token\":\"NSE_EQ|ACME\"}}}")));
        // after wait-duration (1s) the next call probes half-open; a success closes the breaker
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.ltp("ACME")).isEqualTo(10000L);
            assertThat(registry.circuitBreaker("upstox").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        });
    }
```
Run: `mvn test -Dtest=UpstoxResilienceIT` → FAIL (`UNAVAILABLE`/fallback undefined; no annotations).

- [ ] **Step 4: Annotate the client + add the fallback + sentinel** in `marketdata/UpstoxClient.java`. Add imports `io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker`, `io.github.resilience4j.retry.annotation.Retry`, and a constant + fallback:
```java
    static final long UNAVAILABLE = -1L; // fallback sentinel: caller retains last-known price, publishes nothing

    @CircuitBreaker(name = "upstox", fallbackMethod = "ltpFallback")
    @Retry(name = "upstox")
    long ltp(String symbol) { /* unchanged body from Task 1 */ }

    @SuppressWarnings("unused") // invoked reflectively by the Resilience4j aspect
    long ltpFallback(String symbol, Throwable t) {
        return UNAVAILABLE;
    }
```

- [ ] **Step 5: Run it — expect PASS:** `mvn test -Dtest=UpstoxResilienceIT` (slow → timeout → fallback; 5×500 → circuit OPEN → fallback). Also re-run `UpstoxClientIT` (happy path still returns real paise).

- [ ] **Step 6: Write ADR-0010** `docs/adr/0010-resilient-external-market-data-adapter.md` (format per 0008/0009: `# 0010: …` → Status/Date → Context/Decision/Consequences). Content: market data via a scheduled, Resilience4j-guarded external adapter; **retain-last-known fallback** (never publish a stale price); **last-writer-wins** coexistence with internal-trade prices; **annotation-driven `resilience4j-spring-boot4:2.4.0` + `aspectjweaver`** (spike-verified) and the `spring-boot-starter-aop` no-4.x-GA gotcha; sync-`RestClient` uses a read-timeout, not `@TimeLimiter`.

- [ ] **Step 7: Commit** (stash SecurityConfig, `scripts/gate.sh` green, restore):
```bash
git add pom.xml src/main/resources/application.yaml \
        src/main/java/io/github/ajayaj724/tradecore/marketdata/UpstoxClient.java \
        src/test/java/io/github/ajayaj724/tradecore/marketdata/UpstoxResilienceIT.java \
        docs/adr/0010-resilient-external-market-data-adapter.md
git commit -m "feat: Resilience4j guard on Upstox client — retry, circuit breaker, fallback"
```

---

### Task 3: Scheduled feed + `applyExternalPrice`

**Files:**
- Modify: `marketdata/MarketDataService.java` (add `applyExternalPrice`), `src/main/resources/application.yaml` (poll interval)
- Create: `marketdata/UpstoxPriceFeed.java`
- Test: `marketdata/UpstoxPriceFeedIT.java`

**Interfaces:**
- Consumes: `UpstoxClient.ltp` (Tasks 1-2), `MarketDataService`. Produces: `MarketDataService.applyExternalPrice(String symbol, long price, java.time.Instant observedAt)`; `UpstoxPriceFeed` holding `lastSuccess` (`AtomicReference<Instant>`, read by Task 4's gauge).

- [ ] **Step 1: Add `applyExternalPrice` to `MarketDataService`** (mirrors `onTrade`'s upsert+publish at `:32-41`, minus event-id idempotency — a poll is a naturally idempotent overwrite):
```java
    @Transactional
    public void applyExternalPrice(String symbol, long price, java.time.Instant observedAt) {
        jdbc.sql("insert into marketdata.last_price (symbol, price) values (:s, :p)"
                        + " on conflict (symbol) do update set price = :p")
                .param("s", symbol)
                .param("p", price)
                .update();
        events.publishEvent(new PriceUpdated(UUID.randomUUID(), symbol, price, observedAt));
    }
```

- [ ] **Step 2: Add poll interval** to `application.yaml`: `tradecore.upstox.poll.fixed-delay-ms: 5000` and `tradecore.upstox.poll.initial-delay-ms: 5000`. (Mirror the reconciliation pattern; also add a large `initial-delay-ms` to `src/test/resources/application.yaml` so the poller doesn't auto-fire during unrelated ITs — see the 2C scheduler-in-tests lesson.)

- [ ] **Step 3: Write the failing feed IT** `marketdata/UpstoxPriceFeedIT.java` — WireMock returns a price for ACME; call `feed.pollOnce()` (the directly-invocable poll body); assert `PriceUpdated` propagates (Awaitility until `marketDataService.lastPrice("ACME")` equals the fed paise). Second test: WireMock 500 → `pollOnce()` → last price unchanged, `lastSuccess` not advanced. Run → FAIL (`UpstoxPriceFeed` undefined).

- [ ] **Step 4: Create `UpstoxPriceFeed`** `marketdata/UpstoxPriceFeed.java`:
```java
package io.github.ajayaj724.tradecore.marketdata;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls Upstox LTP for the configured symbols and applies successful prices to the read-model. */
@Component
class UpstoxPriceFeed {

    private final UpstoxClient client;
    private final MarketDataService marketData;
    private final UpstoxProperties props;
    private final Clock clock;
    private final AtomicReference<Instant> lastSuccess;

    UpstoxPriceFeed(UpstoxClient client, MarketDataService marketData, UpstoxProperties props, Clock clock) {
        this.client = client;
        this.marketData = marketData;
        this.props = props;
        this.clock = clock;
        this.lastSuccess = new AtomicReference<>(clock.instant());
    }

    @Scheduled(
            initialDelayString = "${tradecore.upstox.poll.initial-delay-ms:5000}",
            fixedDelayString = "${tradecore.upstox.poll.fixed-delay-ms:5000}")
    public void pollOnce() {
        Instant observedAt = clock.instant();
        boolean anySuccess = false;
        for (String symbol : props.instrumentKeys().keySet()) {
            long price = client.ltp(symbol); // resilient; UNAVAILABLE on failure/open circuit
            if (price != UpstoxClient.UNAVAILABLE) {
                marketData.applyExternalPrice(symbol, price, observedAt);
                anySuccess = true;
            }
        }
        if (anySuccess) {
            lastSuccess.set(observedAt);
        }
    }

    Instant lastSuccess() {
        return lastSuccess.get();
    }
}
```

- [ ] **Step 5: Run it — expect PASS:** `mvn test -Dtest=UpstoxPriceFeedIT`. Confirm `mvn test -Dtest=ModularityTests` green (feed publishes only `PriceUpdated`; no new cross-module sync).

- [ ] **Step 6: Commit** (stash/gate/restore):
```bash
git add src/main/java/io/github/ajayaj724/tradecore/marketdata/UpstoxPriceFeed.java \
        src/main/java/io/github/ajayaj724/tradecore/marketdata/MarketDataService.java \
        src/main/resources/application.yaml src/test/resources/application.yaml \
        src/test/java/io/github/ajayaj724/tradecore/marketdata/UpstoxPriceFeedIT.java
git commit -m "feat: scheduled Upstox price feed into marketdata via applyExternalPrice"
```

---

### Task 4: Observability — staleness gauge + Grafana panels

**Files:**
- Create: `marketdata/UpstoxFeedMetrics.java` (staleness gauge)
- Modify: `infra/grafana/provisioning/dashboards/tradecore.json` (+2 panels), `src/test/.../observability/GrafanaDashboardTest.java`
- Test: `marketdata/FeedStalenessIT.java`

**Interfaces:**
- Consumes: `UpstoxPriceFeed.lastSuccess()`, `Clock`, `MeterRegistry`. Produces: gauge `tradecore.marketdata.feed.staleness.seconds`; Resilience4j's own `resilience4j_circuitbreaker_state` (auto, via the starter).

- [ ] **Step 1: Write the failing staleness IT** `marketdata/FeedStalenessIT.java`: autowire `MeterRegistry`; assert `registry.get("tradecore.marketdata.feed.staleness.seconds").gauge().value()` is `>= 0` and increases relative to `lastSuccess` (a fresh context has a small non-negative staleness). Also assert `registry.find("resilience4j.circuitbreaker.state").gauges()` is non-empty (starter binder). Run → FAIL (gauge absent).

- [ ] **Step 2: Create the gauge** `marketdata/UpstoxFeedMetrics.java`:
```java
package io.github.ajayaj724.tradecore.marketdata;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Publishes seconds since the Upstox feed last succeeded — a stuck/degraded feed rises even when the breaker is quiet. */
@Component
class UpstoxFeedMetrics {

    private final UpstoxPriceFeed feed;
    private final Clock clock;

    UpstoxFeedMetrics(UpstoxPriceFeed feed, Clock clock, MeterRegistry registry) {
        this.feed = feed;
        this.clock = clock;
        Gauge.builder("tradecore.marketdata.feed.staleness.seconds", this, UpstoxFeedMetrics::stalenessSeconds)
                .description("seconds since the Upstox market-data feed last succeeded")
                .register(registry);
    }

    double stalenessSeconds() {
        return Duration.between(feed.lastSuccess(), clock.instant()).toSeconds();
    }
}
```

- [ ] **Step 3: Run it — expect PASS:** `mvn test -Dtest=FeedStalenessIT`.

- [ ] **Step 4: Add two Grafana panels** to `infra/grafana/provisioning/dashboards/tradecore.json` (new `panels[]` entries, ids 7 & 8, next grid row): "Upstox circuit-breaker state" → `resilience4j_circuitbreaker_state{name="upstox"}`; "Market-data feed staleness (s)" → `tradecore_marketdata_feed_staleness_seconds`.

- [ ] **Step 5: Extend the dashboard validation test** `GrafanaDashboardTest.java` — add `assertThat(json).contains("resilience4j_circuitbreaker_state");` and `assertThat(json).contains("tradecore_marketdata_feed_staleness_seconds");`, and bump the panel-count assertion to `>= 8`. Run: `mvn test -Dtest=GrafanaDashboardTest` → PASS.

- [ ] **Step 6: Commit** (stash/gate/restore):
```bash
git add src/main/java/io/github/ajayaj724/tradecore/marketdata/UpstoxFeedMetrics.java \
        infra/grafana/provisioning/dashboards/tradecore.json \
        src/test/java/io/github/ajayaj724/tradecore/observability/GrafanaDashboardTest.java \
        src/test/java/io/github/ajayaj724/tradecore/marketdata/FeedStalenessIT.java
git commit -m "feat: feed-staleness gauge + Grafana panels for the Upstox adapter"
```

---

### Task 5: Closeout — README + final gate

**Files:** Modify `README.md`.

- [ ] **Step 1: Update `README.md`** — in `## Observability`/`## Stack`, note the resilient Upstox market-data adapter (scheduled poll, Resilience4j circuit breaker + retry + retain-last-known fallback, feed-staleness gauge, two new Grafana panels). Add a one-line runbook note: **a running Grafana needs a restart to pick up the new panels** (provider configs load at startup — the 3A verification gotcha). Add: the feed is stub-driven; set `tradecore.upstox.base-url` + `tradecore.upstox.access-token` to point at the real Upstox sandbox without code changes.

- [ ] **Step 2: Run the full gate** `scripts/gate.sh` (stash SecurityConfig first, restore after) — must be green.

- [ ] **Step 3: Commit** `git add README.md && git commit -m "docs: README Upstox resilient market-data adapter; Phase 3A complete"`.

- [ ] **Step 4: Finish the branch** — Phase 3A complete. Use `superpowers:finishing-a-development-branch` for `feat/phase3a-upstox-marketdata`.

---

## Self-Review

**Spec coverage:** §1 feed adapter → Tasks 1-3; WireMock stub + fault-injection → Tasks 1-2; observability → Task 4; §2 decisions all reflected (feed-role, stubbed real-contract, scheduled poll, retry/CB/fallback, retain-last-known, inside `marketdata`, last-writer-wins). §4 resilience — note the sync-`RestClient` read-timeout refinement (spec said "TimeLimiter"; §Global Constraints documents why the read-timeout is used instead, same observable behavior). §5 tests: happy (T1), timeout+circuit-open (T2), propagation+degraded (T3), staleness+dashboard (T4). §6 observability → T4. §8 deps → T1/T2 (spike-verified). §11 ADR-0010 → T2. §12 DoD → T5 + the per-task ITs. ✅

**Placeholder scan:** No "TBD"/"handle appropriately". Two flagged verify-at-impl items are real API-surface checks, not placeholders: (a) the Boot-4.1 `ClientHttpRequestFactorySettings`/`ClientHttpRequestFactoryBuilder` read-timeout API (T1 step 6) — invoke debugging-spring-boot-4; (b) the exact `@JsonProperty` behavior is pinned. Task 3 step 3 and Task 4 step 1 describe the failing test in prose with concrete assertions rather than full listings — the implementer writes them from the given assertions and the T1/T2 WireMock IT template (referenced), which is complete code.

**Type consistency:** `ltp(String) -> long`; `UNAVAILABLE = -1L` sentinel checked in the feed; `applyExternalPrice(String, long, Instant)`; `toPaise(BigDecimal) -> long`; gauge `tradecore.marketdata.feed.staleness.seconds` ↔ prometheus `tradecore_marketdata_feed_staleness_seconds` (asserted in T4). CircuitBreaker instance name `"upstox"` consistent across annotation, config, and test. Money `long` paise except the single `BigDecimal` parse boundary in `toPaise`. ✅
