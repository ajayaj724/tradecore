# Phase 2B — Portfolio, Prices & P&L Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. Every task ends with `mvn spotless:apply && mvn verify` green (the `tradecore-quality-gate` skill) before its commit — implied, not repeated.

**Goal:** `portfolio` becomes the source of truth for positions & P&L (integer math), `marketdata` supplies last prices (seeded + trade-derived), and risk's holdings side rewires to `available_holdings = settled_holdings(portfolio-fed) − holds` — the 2A pattern applied to shares.

**Architecture:** Symmetric to 2A. New `marketdata` + `portfolio` modules consume the enriched `TradeExecuted`; `portfolio` publishes `HoldingsPosted` and `marketdata` publishes `PriceUpdated` (both in `shared`). `risk` gains a `HoldingsPosted` listener and extends its `TradeExecuted` handler to release the seller's share hold. All new events fan into `shared` (no new module-to-module edges).

## Global Constraints

Inherits 1B/2A constraints verbatim (money = `BIGINT`; boundaries are law; every listener idempotent + duplicate-delivery test; Flyway roll-forward, ascending by build order; deterministic `Clock`; **`JdbcClient` timestamp params use `OffsetDateTime.now(clock)`**; ArchUnit; Checkstyle ≤40 lines/≤10 cyclomatic/≤120 cols; PMD cognitive ≤15; JaCoCo ≥80%; NullAway; no Lombok; constructor injection). Migrations continue from **V8** (V1–V7 exist). New cross-boundary events live in `shared`.

---

### Task 1: `shared` events + `marketdata` module

**Files:**
- Create: `src/main/java/io/github/ajayaj724/tradecore/shared/PriceUpdated.java`
- Create: `src/main/java/io/github/ajayaj724/tradecore/shared/HoldingsPosted.java`
- Create: `src/main/resources/db/migration/V8__marketdata.sql`
- Create: `src/main/java/io/github/ajayaj724/tradecore/marketdata/MarketDataService.java`
- Create: `src/main/java/io/github/ajayaj724/tradecore/marketdata/MarketDataListener.java`
- Create: `src/test/java/io/github/ajayaj724/tradecore/marketdata/MarketDataServiceIT.java`

**Interfaces:**
- Produces: `record PriceUpdated(UUID eventId, String symbol, long price, Instant occurredAt)`; `record HoldingsPosted(UUID eventId, String account, String symbol, long qty, Instant occurredAt)`; `MarketDataService.onTrade(TradeExecuted)` + `long lastPrice(String symbol)`.

- [ ] **Step 1: V8 migration**

`V8__marketdata.sql`:

```sql
CREATE SCHEMA IF NOT EXISTS marketdata;

CREATE TABLE marketdata.last_price (
    symbol TEXT PRIMARY KEY,
    price  BIGINT NOT NULL
);

CREATE TABLE marketdata.processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

INSERT INTO marketdata.last_price (symbol, price) VALUES ('ACME', 10000), ('INFY', 150000);
```

- [ ] **Step 2: Write the marketdata IT first**

`MarketDataServiceIT.java`:

```java
package io.github.ajayaj724.tradecore.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class MarketDataServiceIT {

    private final MarketDataService md;

    @Autowired
    MarketDataServiceIT(MarketDataService md) {
        this.md = md;
    }

    private static TradeExecuted trade(String symbol, long price) {
        return new TradeExecuted(UUID.randomUUID(), 1L, 2L, "trader1", "trader2", symbol, price, 5L, Instant.EPOCH);
    }

    @Test
    void tradeUpdatesLastPrice() {
        md.onTrade(trade("ACME", 12345L));
        assertThat(md.lastPrice("ACME")).isEqualTo(12345L);
    }

    @Test
    void redeliveryOfSameTradeIsANoOp() {
        TradeExecuted t = trade("ACME", 11111L);
        md.onTrade(t);
        md.onTrade(t); // same eventId → deduped
        assertThat(md.lastPrice("ACME")).isEqualTo(11111L);
    }
}
```

- [ ] **Step 3: Run it — verify it fails to compile** (`mvn -q -Dtest=MarketDataServiceIT test`; `MarketDataService` missing).

- [ ] **Step 4: Implement events + service + listener**

`shared/PriceUpdated.java`:

```java
package io.github.ajayaj724.tradecore.shared;

import java.time.Instant;
import java.util.UUID;

public record PriceUpdated(UUID eventId, String symbol, long price, Instant occurredAt) {}
```

`shared/HoldingsPosted.java`:

```java
package io.github.ajayaj724.tradecore.shared;

import java.time.Instant;
import java.util.UUID;

public record HoldingsPosted(UUID eventId, String account, String symbol, long qty, Instant occurredAt) {}
```

`marketdata/MarketDataService.java` (upsert last price, publish `PriceUpdated`, idempotent):

```java
package io.github.ajayaj724.tradecore.marketdata;

import io.github.ajayaj724.tradecore.shared.PriceUpdated;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Last prices: seeded openings, then updated from each trade's fill price. */
@Service
public class MarketDataService {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    MarketDataService(JdbcClient jdbc, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void onTrade(TradeExecuted trade) {
        if (alreadyProcessed(trade.eventId())) {
            return;
        }
        jdbc.sql("insert into marketdata.last_price (symbol, price) values (:s, :p)"
                        + " on conflict (symbol) do update set price = :p")
                .param("s", trade.symbol())
                .param("p", trade.price())
                .update();
        jdbc.sql("insert into marketdata.processed_event (event_id, processed_at) values (:e, :t)")
                .param("e", trade.eventId())
                .param("t", OffsetDateTime.now(clock))
                .update();
        events.publishEvent(new PriceUpdated(UUID.randomUUID(), trade.symbol(), trade.price(), clock.instant()));
    }

    public long lastPrice(String symbol) {
        return jdbc.sql("select price from marketdata.last_price where symbol = :s")
                .param("s", symbol)
                .query(Long.class)
                .single();
    }

    private boolean alreadyProcessed(UUID eventId) {
        return jdbc.sql("select count(*) from marketdata.processed_event where event_id = :e")
                        .param("e", eventId)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
```

`marketdata/MarketDataListener.java`:

```java
package io.github.ajayaj724.tradecore.marketdata;

import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class MarketDataListener {

    private final MarketDataService md;

    MarketDataListener(MarketDataService md) {
        this.md = md;
    }

    @ApplicationModuleListener
    void on(TradeExecuted trade) {
        md.onTrade(trade);
    }
}
```

- [ ] **Step 5: Run it — verify green** (`mvn -q -Dtest=MarketDataServiceIT test`).

- [ ] **Step 6: Full gate + commit**

```bash
git add src/main/java/io/github/ajayaj724/tradecore/shared/PriceUpdated.java \
  src/main/java/io/github/ajayaj724/tradecore/shared/HoldingsPosted.java \
  src/main/resources/db/migration/V8__marketdata.sql \
  src/main/java/io/github/ajayaj724/tradecore/marketdata/ \
  src/test/java/io/github/ajayaj724/tradecore/marketdata/MarketDataServiceIT.java
git commit -m "feat: marketdata module — seeded + trade-derived last prices, PriceUpdated"
```

---

### Task 2: `portfolio` module — positions, cost basis, P&L

**Files:**
- Create: `src/main/resources/db/migration/V9__portfolio.sql`
- Create: `src/main/java/io/github/ajayaj724/tradecore/portfolio/PortfolioService.java`
- Create: `src/main/java/io/github/ajayaj724/tradecore/portfolio/PortfolioListener.java`
- Create: `src/test/java/io/github/ajayaj724/tradecore/portfolio/PortfolioServiceIT.java`

**Interfaces:**
- Consumes: `shared.TradeExecuted`, `shared.PriceUpdated`, `shared.HoldingsPosted` (published).
- Produces: `PortfolioService.onTrade(TradeExecuted)`, `onPrice(PriceUpdated)`; `long positionQty(account, symbol)`, `long realizedPnl(account, symbol)`, `long unrealizedPnl(account, symbol)`.

- [ ] **Step 1: V9 migration**

`V9__portfolio.sql`:

```sql
CREATE SCHEMA IF NOT EXISTS portfolio;

CREATE TABLE portfolio.position (
    account      TEXT   NOT NULL,
    symbol       TEXT   NOT NULL,
    total_qty    BIGINT NOT NULL DEFAULT 0,
    total_cost   BIGINT NOT NULL DEFAULT 0,   -- paise
    realized_pnl BIGINT NOT NULL DEFAULT 0,   -- paise
    PRIMARY KEY (account, symbol)
);

CREATE TABLE portfolio.mark_price (
    symbol TEXT PRIMARY KEY,
    price  BIGINT NOT NULL
);

CREATE TABLE portfolio.processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- Opening positions matching risk.settled_holdings (V10), valued at the opening price (10000 paise),
-- so portfolio.total_qty and risk.settled_holdings share one origin and reconcile cleanly.
INSERT INTO portfolio.position (account, symbol, total_qty, total_cost, realized_pnl) VALUES
    ('trader1', 'ACME', 1000, 10000000, 0),
    ('trader2', 'ACME', 1000, 10000000, 0);
```

> **Refines spec §4** ("positions start at zero"): demo accounts get seeded opening ACME positions
> that match the seeded `risk.settled_holdings`, mirroring how 2A's ledger openings matched
> `risk.settled_cash`. Fresh accounts/symbols still start at zero (via `ensurePosition`).

- [ ] **Step 2: Write the portfolio IT first (round-trip conservation)**

`PortfolioServiceIT.java`:

```java
package io.github.ajayaj724.tradecore.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.PriceUpdated;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class PortfolioServiceIT {

    private final PortfolioService portfolio;

    @Autowired
    PortfolioServiceIT(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    private static TradeExecuted trade(String buyer, String seller, long price, long qty) {
        return new TradeExecuted(UUID.randomUUID(), 1L, 2L, buyer, seller, "PF", price, qty, Instant.EPOCH);
    }

    @Test
    void buyBuildsPositionAndCost() {
        portfolio.onTrade(trade("alice", "zoe", 10000L, 5L)); // alice buys 5 @ 100.00

        assertThat(portfolio.positionQty("alice", "PF")).isEqualTo(5L);
        // unrealized once marked: last_price 12000 -> 12000*5 - 10000*5 = 10000
        portfolio.onPrice(new PriceUpdated(UUID.randomUUID(), "PF", 12000L, Instant.EPOCH));
        assertThat(portfolio.unrealizedPnl("alice", "PF")).isEqualTo(10000L);
    }

    @Test
    void sellRealizesIntegerPnlAndRoundTripConserves() {
        portfolio.onTrade(trade("bob", "zoe", 10000L, 5L)); // bob buys 5 @ 100.00 (cost 50000)
        portfolio.onTrade(trade("carol", "bob", 12000L, 5L)); // bob sells all 5 @ 120.00 (proceeds 60000)

        assertThat(portfolio.positionQty("bob", "PF")).isZero();
        assertThat(portfolio.realizedPnl("bob", "PF")).isEqualTo(10000L); // 60000 - 50000
    }

    @Test
    void reapplyingSameTradeIsANoOp() {
        TradeExecuted t = trade("dave", "zoe", 10000L, 4L);
        portfolio.onTrade(t);
        portfolio.onTrade(t); // deduped
        assertThat(portfolio.positionQty("dave", "PF")).isEqualTo(4L);
    }
}
```

- [ ] **Step 3: Run it — verify it fails** (`mvn -q -Dtest=PortfolioServiceIT test`).

- [ ] **Step 4: Implement `PortfolioService` + listener**

`portfolio/PortfolioService.java` (buyer accumulates, seller realizes with integer cost-of-sold; publishes two `HoldingsPosted`; marks from `PriceUpdated`):

```java
package io.github.ajayaj724.tradecore.portfolio;

import io.github.ajayaj724.tradecore.shared.HoldingsPosted;
import io.github.ajayaj724.tradecore.shared.PriceUpdated;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Positions as total_qty + total_cost; average cost derived, P&L integer. */
@Service
public class PortfolioService {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PortfolioService(JdbcClient jdbc, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void onTrade(TradeExecuted trade) {
        if (alreadyProcessed(trade.eventId())) {
            return;
        }
        applyBuy(trade.buyerAccount(), trade.symbol(), trade.price(), trade.quantity());
        applySell(trade.sellerAccount(), trade.symbol(), trade.price(), trade.quantity());
        markProcessed(trade.eventId());
        publishHoldings(trade.buyerAccount(), trade.symbol(), trade.quantity());
        publishHoldings(trade.sellerAccount(), trade.symbol(), -trade.quantity());
    }

    @Transactional
    public void onPrice(PriceUpdated price) {
        if (alreadyProcessed(price.eventId())) {
            return;
        }
        jdbc.sql("insert into portfolio.mark_price (symbol, price) values (:s, :p)"
                        + " on conflict (symbol) do update set price = :p")
                .param("s", price.symbol())
                .param("p", price.price())
                .update();
        markProcessed(price.eventId());
    }

    public long positionQty(String account, String symbol) {
        return scalar("select coalesce(total_qty, 0) from portfolio.position where account = :a and symbol = :s",
                account, symbol);
    }

    public long realizedPnl(String account, String symbol) {
        return scalar("select coalesce(realized_pnl, 0) from portfolio.position where account = :a and symbol = :s",
                account, symbol);
    }

    /** Exact: market value − cost basis = last_price×qty − total_cost. */
    public long unrealizedPnl(String account, String symbol) {
        long qty = positionQty(account, symbol);
        long cost = scalar("select coalesce(total_cost, 0) from portfolio.position where account = :a and symbol = :s",
                account, symbol);
        Long mark = jdbc.sql("select price from portfolio.mark_price where symbol = :s")
                .param("s", symbol)
                .query(Long.class)
                .optional()
                .orElse(null);
        return mark == null ? 0 : mark * qty - cost;
    }

    private void applyBuy(String account, String symbol, long price, long qty) {
        ensurePosition(account, symbol);
        jdbc.sql("update portfolio.position set total_qty = total_qty + :q, total_cost = total_cost + :c"
                        + " where account = :a and symbol = :s")
                .param("q", qty)
                .param("c", price * qty)
                .param("a", account)
                .param("s", symbol)
                .update();
    }

    private void applySell(String account, String symbol, long price, long qty) {
        ensurePosition(account, symbol);
        long totalQty = positionQty(account, symbol);
        long totalCost = scalar(
                "select coalesce(total_cost, 0) from portfolio.position where account = :a and symbol = :s",
                account, symbol);
        long cost = totalQty == 0 ? 0 : totalCost * qty / totalQty;
        jdbc.sql("update portfolio.position set total_qty = total_qty - :q, total_cost = total_cost - :cost,"
                        + " realized_pnl = realized_pnl + :pnl where account = :a and symbol = :s")
                .param("q", qty)
                .param("cost", cost)
                .param("pnl", price * qty - cost)
                .param("a", account)
                .param("s", symbol)
                .update();
    }

    private void ensurePosition(String account, String symbol) {
        jdbc.sql("insert into portfolio.position (account, symbol) values (:a, :s)"
                        + " on conflict (account, symbol) do nothing")
                .param("a", account)
                .param("s", symbol)
                .update();
    }

    private void publishHoldings(String account, String symbol, long qty) {
        events.publishEvent(new HoldingsPosted(UUID.randomUUID(), account, symbol, qty, clock.instant()));
    }

    private long scalar(String sql, String account, String symbol) {
        return jdbc.sql(sql).param("a", account).param("s", symbol).query(Long.class).single();
    }

    private boolean alreadyProcessed(UUID eventId) {
        return jdbc.sql("select count(*) from portfolio.processed_event where event_id = :e")
                        .param("e", eventId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private void markProcessed(UUID eventId) {
        jdbc.sql("insert into portfolio.processed_event (event_id, processed_at) values (:e, :t)")
                .param("e", eventId)
                .param("t", OffsetDateTime.now(clock))
                .update();
    }
}
```

`portfolio/PortfolioListener.java`:

```java
package io.github.ajayaj724.tradecore.portfolio;

import io.github.ajayaj724.tradecore.shared.PriceUpdated;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class PortfolioListener {

    private final PortfolioService portfolio;

    PortfolioListener(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @ApplicationModuleListener
    void onTrade(TradeExecuted trade) {
        portfolio.onTrade(trade);
    }

    @ApplicationModuleListener
    void onPrice(PriceUpdated price) {
        portfolio.onPrice(price);
    }
}
```

- [ ] **Step 5: Run it — verify green** (`mvn -q -Dtest=PortfolioServiceIT test`).

- [ ] **Step 6: Full gate + commit**

```bash
git add src/main/resources/db/migration/V9__portfolio.sql \
  src/main/java/io/github/ajayaj724/tradecore/portfolio/ \
  src/test/java/io/github/ajayaj724/tradecore/portfolio/PortfolioServiceIT.java
git commit -m "feat: portfolio module — integer positions, cost basis, realized/unrealized P&L"
```

---

### Task 3: `risk` holdings rewire — `settled_holdings − holds`

**Files:**
- Create: `src/main/resources/db/migration/V10__risk_holdings_rewire.sql`
- Modify: `src/main/java/io/github/ajayaj724/tradecore/risk/RiskService.java` (`reserveHoldings` rewrite; extend `releaseHold`; add `applyHoldingsPosted`)
- Modify: `src/main/java/io/github/ajayaj724/tradecore/risk/RiskProjectionListener.java` (add `onHoldingsPosted`)
- Modify: `src/test/java/io/github/ajayaj724/tradecore/risk/RiskServiceIT.java` (sell test → new model)
- Create: `src/test/java/io/github/ajayaj724/tradecore/risk/HoldingsProjectionIT.java`

**Interfaces:**
- Produces: `RiskService.reserveHoldings(orderId, account, symbol, qty)` (settled − holds; inserts holdings hold); `applyHoldingsPosted(HoldingsPosted)`; `releaseHold(TradeExecuted)` now releases both buyer cash + seller share holds.

- [ ] **Step 1: V10 migration**

`V10__risk_holdings_rewire.sql`:

```sql
DROP TABLE risk.available_holdings;   -- replaced by settled_holdings + holdings_hold

CREATE TABLE risk.settled_holdings (
    account TEXT   NOT NULL,
    symbol  TEXT   NOT NULL,
    qty     BIGINT NOT NULL,
    PRIMARY KEY (account, symbol)
);

CREATE TABLE risk.holdings_hold (
    order_id      BIGINT PRIMARY KEY,
    account       TEXT   NOT NULL,
    symbol        TEXT   NOT NULL,
    remaining_qty BIGINT NOT NULL
);

INSERT INTO risk.settled_holdings (account, symbol, qty) VALUES
    ('trader1', 'ACME', 1000),
    ('trader2', 'ACME', 1000);
```

- [ ] **Step 2: Write `HoldingsProjectionIT` + update `RiskServiceIT` sell test (first)**

`HoldingsProjectionIT.java`:

```java
package io.github.ajayaj724.tradecore.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.HoldingsPosted;
import io.github.ajayaj724.tradecore.shared.Side;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfig.class)
class HoldingsProjectionIT {

    private final RiskService risk;
    private final JdbcClient jdbc;

    @Autowired
    HoldingsProjectionIT(RiskService risk, JdbcClient jdbc) {
        this.risk = risk;
        this.jdbc = jdbc;
    }

    private long settled(String a, String s) {
        return jdbc.sql("select qty from risk.settled_holdings where account = :a and symbol = :s")
                .param("a", a)
                .param("s", s)
                .query(Long.class)
                .single();
    }

    private long holdQty(long orderId) {
        return jdbc.sql("select coalesce(sum(remaining_qty), 0) from risk.holdings_hold where order_id = :o")
                .param("o", orderId)
                .query(Long.class)
                .single();
    }

    @Test
    void holdingsPostedUpdatesSettledOnceUnderRedelivery() {
        long before = settled("trader1", "ACME");
        HoldingsPosted e = new HoldingsPosted(UUID.randomUUID(), "trader1", "ACME", -10L, Instant.EPOCH);
        risk.applyHoldingsPosted(e);
        risk.applyHoldingsPosted(e); // deduped
        assertThat(settled("trader1", "ACME")).isEqualTo(before - 10L);
    }

    @Test
    void tradeReleasesSellerHoldOnceUnderRedelivery() {
        risk.check(7101L, "trader1", Side.SELL, "ACME", 10000L, 5L); // holds 5 shares (order 7101 sells)
        TradeExecuted t = new TradeExecuted(
                UUID.randomUUID(), 8101L, 7101L, "trader2", "trader1", "ACME", 10000L, 5L, Instant.EPOCH);
        risk.releaseHold(t);
        risk.releaseHold(t); // deduped
        assertThat(holdQty(7101L)).isZero();
    }
}
```

In `RiskServiceIT`, the existing `rejectsSellWhenHoldingsInsufficient` still holds under the new model (trader1 `settled_holdings` ACME = 1000, sell 999999 → available < qty). No code change needed there beyond it compiling against the new schema; verify it passes.

- [ ] **Step 3: Run it — verify it fails** (`mvn -q -Dtest=HoldingsProjectionIT test`; `applyHoldingsPosted` missing).

- [ ] **Step 4: Rewrite `reserveHoldings`, extend `releaseHold`, add `applyHoldingsPosted`**

In `RiskService`, replace `reserveHoldings` (add `orderId`, use `settled_holdings − holds`):

```java
    private RiskDecision reserveHoldings(long orderId, String account, String symbol, long quantity) {
        Long settled = jdbc.sql(
                        "select qty from risk.settled_holdings where account = :a and symbol = :s for update")
                .param("a", account)
                .param("s", symbol)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (settled == null) {
            return new RiskDecision.Rejected("insufficient holdings");
        }
        long held = jdbc.sql(
                        "select coalesce(sum(remaining_qty), 0) from risk.holdings_hold"
                                + " where account = :a and symbol = :s")
                .param("a", account)
                .param("s", symbol)
                .query(Long.class)
                .single();
        if (settled - held < quantity) {
            return new RiskDecision.Rejected("insufficient holdings");
        }
        jdbc.sql("insert into risk.holdings_hold (order_id, account, symbol, remaining_qty)"
                        + " values (:o, :a, :s, :q)")
                .param("o", orderId)
                .param("a", account)
                .param("s", symbol)
                .param("q", quantity)
                .update();
        return new RiskDecision.Approved();
    }
```

Update the `check` dispatch to pass `orderId` to the sell path:

```java
        return side == Side.BUY
                ? reserveCash(orderId, account, price, quantity)
                : reserveHoldings(orderId, account, symbol, quantity);
```

Extend `releaseHold` to also release the seller's share hold (append before `markProcessed`):

```java
        jdbc.sql("update risk.holdings_hold set remaining_qty = remaining_qty - :q where order_id = :o")
                .param("q", trade.quantity())
                .param("o", trade.sellOrderId())
                .update();
        jdbc.sql("delete from risk.holdings_hold where order_id = :o and remaining_qty <= 0")
                .param("o", trade.sellOrderId())
                .update();
```

Add `applyHoldingsPosted` (imports: `io.github.ajayaj724.tradecore.shared.HoldingsPosted`):

```java
    @Transactional
    public void applyHoldingsPosted(HoldingsPosted event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        jdbc.sql("update risk.settled_holdings set qty = qty + :q where account = :a and symbol = :s")
                .param("q", event.qty())
                .param("a", event.account())
                .param("s", event.symbol())
                .update();
        markProcessed(event.eventId());
    }
```

- [ ] **Step 5: Add the `HoldingsPosted` listener**

In `RiskProjectionListener`, add (import `HoldingsPosted`):

```java
    @ApplicationModuleListener
    void onHoldingsPosted(HoldingsPosted event) {
        risk.applyHoldingsPosted(event);
    }
```

- [ ] **Step 6: Run it — verify green** (`mvn -q -Dtest=HoldingsProjectionIT,RiskServiceIT test`).

- [ ] **Step 7: Full gate + commit**

Note: `OrderSubmissionIT` / `OrderOwnershipIT` sells (if any) now go through the hold model; buys are unaffected. Confirm green.

```bash
git add src/main/resources/db/migration/V10__risk_holdings_rewire.sql \
  src/main/java/io/github/ajayaj724/tradecore/risk/ \
  src/test/java/io/github/ajayaj724/tradecore/risk/
git commit -m "feat: risk holdings rewire — available_holdings = settled(portfolio-fed) - holds"
```

---

### Task 4: End-to-end proof — position, mark, realized P&L, holdings settle

**Files:**
- Create: `src/test/java/io/github/ajayaj724/tradecore/orders/PositionSettlementEndToEndIT.java`

- [ ] **Step 1: Write the e2e IT** (@DirtiesContext for a fresh engine/seeds; trader2 sells, trader1 buys → fill; assert trader1 gains a position and `portfolio.total_qty == risk.settled_holdings`):

```java
package io.github.ajayaj724.tradecore.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class PositionSettlementEndToEndIT {

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    @Autowired
    PositionSettlementEndToEndIT(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private static RequestPostProcessor trader(String u) {
        return jwt().jwt(j -> j.claim("preferred_username", u)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    private void submit(String user, String side, String key) throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(trader(user))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"" + side + "\",\"price\":10000,\"quantity\":5}"))
                .andExpect(status().isCreated());
    }

    private long portfolioQty(String a) {
        return jdbc.sql("select coalesce(total_qty,0) from portfolio.position where account = :a and symbol = 'ACME'")
                .param("a", a)
                .query(Long.class)
                .single();
    }

    private long settledHoldings(String a) {
        return jdbc.sql("select qty from risk.settled_holdings where account = :a and symbol = 'ACME'")
                .param("a", a)
                .query(Long.class)
                .single();
    }

    @Test
    void fillBuildsPositionAndSettlesHoldings() throws Exception {
        submit("trader2", "SELL", "pos-sell");
        submit("trader1", "BUY", "pos-buy");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(portfolioQty("trader1")).isEqualTo(1005L); // opening 1000 + bought 5
            assertThat(portfolioQty("trader2")).isEqualTo(995L); // opening 1000 - sold 5
            // reconciliation-lite: portfolio and risk share one seeded origin, so they stay equal
            assertThat(settledHoldings("trader1")).isEqualTo(portfolioQty("trader1"));
            assertThat(settledHoldings("trader2")).isEqualTo(portfolioQty("trader2"));
        });
    }
}
```

- [ ] **Step 2: Run it — verify green** (`mvn -q -Dtest=PositionSettlementEndToEndIT test`).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/github/ajayaj724/tradecore/orders/PositionSettlementEndToEndIT.java
git commit -m "test: end-to-end position build + holdings settlement proof"
```

---

### Task 5: Closeout — ADR + gate

**Files:**
- Create: `docs/adr/0008-integer-average-cost-and-pnl.md`

- [ ] **Step 1: Write the ADR** — `total_cost + total_qty` (no stored average), integer cost-of-sold with residual carried on partial sales, division-free unrealized P&L (`last_price×qty − total_cost`), average-cost method (not FIFO lots). Note the holdings rewire reuses ADR-0007. Follow the `0001` format.

- [ ] **Step 2: Final whole-suite gate + commit**

```bash
git add docs/adr/0008-integer-average-cost-and-pnl.md
git commit -m "docs: ADR for integer average-cost and P&L"
```

- [ ] **Step 3: Definition of done** — buy builds position + cost; sell realizes integer P&L with round-trip conservation; marketdata `PriceUpdated` marks unrealized P&L exactly; risk sell check = `settled_holdings − holds`, seller hold releases on fill; every new/extended listener has a duplicate-delivery test; `portfolio.total_qty` ties to `risk.settled_holdings` after a fill; `verify()` green; JaCoCo ≥ 80%; ADR written.

---

## Self-Review (done at authoring time)

**Spec coverage:** marketdata (Task 1), portfolio positions/P&L (Task 2), holdings rewire (Task 3), e2e + reconciliation-lite (Task 4), ADR (Task 5). Cost basis = `total_cost + total_qty` per §2; unrealized division-free per §4; holdings mirror 2A per §6.

**Placeholder scan:** no TBD/TODO; every step has code or an exact command; the ADR body is described with required content (0001 format), as in prior phases.

**Type consistency:** `PriceUpdated(eventId, symbol, price, occurredAt)` and `HoldingsPosted(eventId, account, symbol, qty, occurredAt)` match between publishers (marketdata/portfolio) and consumers (portfolio/risk). `reserveHoldings` gains `orderId` matching the `check` dispatch. `releaseHold` releases cash (buyOrderId) + shares (sellOrderId). Migrations ascend V8 → V9 → V10 by task order.
