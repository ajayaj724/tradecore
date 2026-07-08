# Phase 2A — Ledger & Cash Settlement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Every task ends with `mvn spotless:apply && mvn verify` green (the `tradecore-quality-gate` skill) before its commit — implied by every "Commit" step, not repeated.

**Goal:** A filled trade moves real settled cash between buyer and seller through a double-entry `ledger`, and `risk`'s buy check runs off `available = settled − holds` (settling the ADR-0003 cash rewire).

**Architecture:** New `ledger` module (postings-only double-entry, cash = `Σ postings`) consumes an enriched `TradeExecuted` (now carrying buyer/seller accounts, stamped by `execution`) and publishes signed-delta `CashPosted` events. `risk` refactors its cash side to a ledger-fed `settled_cash` read-model plus risk-owned `cash_hold`s, with two idempotent listeners.

**Tech Stack:** Same as 1B — Spring Boot 4.1, Java 25, Spring Modulith 2.1, Spring Data JDBC + `JdbcClient`, Flyway, PostgreSQL 18, Testcontainers 2.x, jqwik, JUnit 5, AssertJ.

## Global Constraints

Inherits 1B's global constraints verbatim (money = `BIGINT` minor units; module boundaries are law; every listener idempotent + duplicate-delivery test; Flyway roll-forward, migrations ascend by build order; RFC 9457 Problem Details; deterministic `Clock`; **`JdbcClient` timestamp params use `OffsetDateTime.now(clock)`** — pgjdbc can't infer `Instant`; ArchUnit rules; Checkstyle method ≤40 lines / cyclomatic ≤10 / line ≤120; PMD cognitive ≤15; JaCoCo ≥80%; NullAway on the base package; no Lombok; constructor injection). Migrations continue from **V5** (V1–V4 exist).

New cross-boundary event contracts (`CashPosted`, the enriched `TradeExecuted`) live in the **`shared`** module (the acyclic-contracts decision from ADR-0005).

---

### Task 1: Enrich `TradeExecuted` with buyer/seller accounts

Add accounts to the shared contract; `execution` retains an `orderId → account` projection from `OrderAccepted` and stamps both accounts on publish. Update every construction site.

**Files:**
- Modify: `src/main/java/io/github/ajayaj724/tradecore/shared/TradeExecuted.java`
- Create: `src/main/resources/db/migration/V5__execution_order_account.sql`
- Modify: `src/main/java/io/github/ajayaj724/tradecore/execution/EmbeddedMatchingVenue.java`
- Modify (tests, absorb the new fields): `src/test/java/io/github/ajayaj724/tradecore/shared/EventContractsTest.java`, `src/test/java/io/github/ajayaj724/tradecore/orders/OrderFillListenerIT.java`
- Modify (assert enrichment): `src/test/java/io/github/ajayaj724/tradecore/execution/EmbeddedMatchingVenueIT.java`

**Interfaces:**
- Produces: `record TradeExecuted(UUID eventId, long buyOrderId, long sellOrderId, String buyerAccount, String sellerAccount, String symbol, long price, long quantity, Instant occurredAt)`

- [ ] **Step 1: Update the adapter IT to assert enrichment (write the expectation first)**

In `EmbeddedMatchingVenueIT.crossingOrdersProduceATrade`, both orders use account `trader1` (the `accepted(...)` helper), so add:

```java
        assertThat(trades.getFirst().buyerAccount()).isEqualTo("trader1");
        assertThat(trades.getFirst().sellerAccount()).isEqualTo("trader1");
```

- [ ] **Step 2: Run it — verify it fails to compile**

Run: `mvn -q -Dtest=EmbeddedMatchingVenueIT test`
Expected: FAIL — `TradeExecuted` has no `buyerAccount()`.

- [ ] **Step 3: Add the fields to the shared contract**

`shared/TradeExecuted.java` (accounts paired after the order ids):

```java
public record TradeExecuted(
        UUID eventId,
        long buyOrderId,
        long sellOrderId,
        String buyerAccount,
        String sellerAccount,
        String symbol,
        long price,
        long quantity,
        Instant occurredAt) {}
```

- [ ] **Step 4: Add the `order_account` projection migration**

`V5__execution_order_account.sql`:

```sql
CREATE TABLE execution.order_account (
    order_id BIGINT PRIMARY KEY,
    account  TEXT NOT NULL
);
```

- [ ] **Step 5: Record accounts + stamp the trade in `EmbeddedMatchingVenue`**

In `submit(...)`, after the dedup guard and before matching, record the incoming order's account; then look the accounts up per fill. Replace the body of `submit` and add two helpers:

```java
    @Override
    public List<TradeExecuted> submit(OrderAccepted order) {
        if (alreadyProcessed(order.eventId())) {
            return List.of();
        }
        rememberAccount(order.orderId(), order.account());
        List<Fill> fills =
                engine.submit(order.symbol(), order.orderId(), engineSide(order.side()), order.price(), order.quantity());
        markProcessed(order.eventId());
        List<TradeExecuted> trades = new ArrayList<>();
        for (Fill f : fills) {
            TradeExecuted trade = new TradeExecuted(
                    UUID.randomUUID(),
                    f.buyOrderId(),
                    f.sellOrderId(),
                    accountOf(f.buyOrderId()),
                    accountOf(f.sellOrderId()),
                    order.symbol(),
                    f.price(),
                    f.quantity(),
                    clock.instant());
            events.publishEvent(trade);
            trades.add(trade);
        }
        return trades;
    }

    private void rememberAccount(long orderId, String account) {
        jdbc.sql("insert into execution.order_account (order_id, account) values (:id, :a) on conflict (order_id) do nothing")
                .param("id", orderId)
                .param("a", account)
                .update();
    }

    private String accountOf(long orderId) {
        return jdbc.sql("select account from execution.order_account where order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }
```

- [ ] **Step 6: Fix the remaining construction sites**

`EventContractsTest.java:14`:

```java
        TradeExecuted t = new TradeExecuted(id, 2L, 1L, "trader1", "trader2", "ACME", 10000L, 5L, Instant.EPOCH);
```

`OrderFillListenerIT.java` both `new TradeExecuted(...)` calls — the inserted orders are `trader1` (buy) and `trader2` (sell):

```java
        TradeExecuted trade = new TradeExecuted(UUID.randomUUID(), buyId, sellId, "trader1", "trader2", "ACME", 10000L, 5L, Instant.EPOCH);
```
```java
        TradeExecuted trade = new TradeExecuted(UUID.randomUUID(), buyId, sellId, "trader1", "trader2", "ACME", 10000L, 3L, Instant.EPOCH);
```

- [ ] **Step 7: Run the affected tests — verify green**

Run: `mvn -q -Dtest=EmbeddedMatchingVenueIT,EventContractsTest test` then the fill IT under failsafe via the full gate in Step 8.
Expected: PASS.

- [ ] **Step 8: Full gate + commit**

Run the `tradecore-quality-gate` skill.

```bash
git add src/main/java/io/github/ajayaj724/tradecore/shared/TradeExecuted.java \
  src/main/resources/db/migration/V5__execution_order_account.sql \
  src/main/java/io/github/ajayaj724/tradecore/execution/EmbeddedMatchingVenue.java \
  src/test/java/io/github/ajayaj724/tradecore/shared/EventContractsTest.java \
  src/test/java/io/github/ajayaj724/tradecore/orders/OrderFillListenerIT.java \
  src/test/java/io/github/ajayaj724/tradecore/execution/EmbeddedMatchingVenueIT.java
git commit -m "feat: enrich TradeExecuted with buyer/seller accounts (execution stamps them)"
```

---

### Task 2: `ledger` module — double-entry cash + `CashPosted`

New module: postings-only double-entry, opening deposits seeded balanced, `TradeExecuted` listener posts a balanced transaction and publishes signed-delta `CashPosted` events.

**Files:**
- Create: `src/main/resources/db/migration/V6__ledger.sql`
- Create: `src/main/java/io/github/ajayaj724/tradecore/shared/CashPosted.java`
- Create: `src/main/java/io/github/ajayaj724/tradecore/ledger/LedgerService.java`
- Create: `src/main/java/io/github/ajayaj724/tradecore/ledger/LedgerListener.java`
- Create: `src/test/java/io/github/ajayaj724/tradecore/ledger/LedgerServiceIT.java`

**Interfaces:**
- Consumes: `shared.TradeExecuted` (now with accounts), `Clock`, `ApplicationEventPublisher`, `JdbcClient`.
- Produces:
  - `record CashPosted(UUID eventId, String account, long amount, Instant occurredAt)` (`amount` = signed delta)
  - `LedgerService.post(TradeExecuted)` (idempotent, balanced) ; `long balanceOf(String account)` (public — `Σ postings`)

- [ ] **Step 1: V6 migration**

`V6__ledger.sql`:

```sql
CREATE SCHEMA IF NOT EXISTS ledger;

CREATE TABLE ledger.posting (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    txn_id     UUID   NOT NULL,
    account    TEXT   NOT NULL,
    amount     BIGINT NOT NULL,   -- signed paise
    kind       TEXT   NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ledger_posting_account_idx ON ledger.posting (account);

CREATE TABLE ledger.processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- Opening deposits, balanced per txn: credit each trader, debit the house capital account.
INSERT INTO ledger.posting (txn_id, account, amount, kind) VALUES
    ('00000000-0000-0000-0000-000000000001', 'trader1',  100000000, 'OPENING'),
    ('00000000-0000-0000-0000-000000000001', 'house',   -100000000, 'OPENING'),
    ('00000000-0000-0000-0000-000000000002', 'trader2',  100000000, 'OPENING'),
    ('00000000-0000-0000-0000-000000000002', 'house',   -100000000, 'OPENING');
```

- [ ] **Step 2: Write the ledger IT first**

`LedgerServiceIT.java` — posting a trade debits the buyer and credits the seller, each transaction balances, and the system conserves cash:

```java
package io.github.ajayaj724.tradecore.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
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
class LedgerServiceIT {

    private final LedgerService ledger;
    private final JdbcClient jdbc;

    @Autowired
    LedgerServiceIT(LedgerService ledger, JdbcClient jdbc) {
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    private static TradeExecuted trade(long qty, long price) {
        return new TradeExecuted(
                UUID.randomUUID(), 1L, 2L, "trader1", "trader2", "ACME", price, qty, Instant.EPOCH);
    }

    @Test
    void postingATradeTransfersCashBuyerToSeller() {
        long buyerBefore = ledger.balanceOf("trader1");
        long sellerBefore = ledger.balanceOf("trader2");

        ledger.post(trade(5L, 9000L)); // 45000 paise

        assertThat(ledger.balanceOf("trader1")).isEqualTo(buyerBefore - 45000L);
        assertThat(ledger.balanceOf("trader2")).isEqualTo(sellerBefore + 45000L);
    }

    @Test
    void everyTransactionBalancesToZero() {
        ledger.post(trade(3L, 10000L));
        Long unbalanced = jdbc.sql(
                        "select count(*) from (select txn_id, sum(amount) s from ledger.posting group by txn_id) t where s <> 0")
                .query(Long.class)
                .single();
        assertThat(unbalanced).isZero();
    }

    @Test
    void redeliveryOfSameTradeIsANoOp() {
        TradeExecuted t = trade(2L, 10000L);
        long before = ledger.balanceOf("trader1");
        ledger.post(t);
        ledger.post(t); // same eventId → deduped
        assertThat(ledger.balanceOf("trader1")).isEqualTo(before - 20000L);
    }
}
```

- [ ] **Step 3: Run it — verify it fails to compile**

Run: `mvn -q -Dtest=LedgerServiceIT test`
Expected: FAIL — `LedgerService` does not exist.

- [ ] **Step 4: Implement `CashPosted`, `LedgerService`, `LedgerListener`**

`shared/CashPosted.java`:

```java
package io.github.ajayaj724.tradecore.shared;

import java.time.Instant;
import java.util.UUID;

public record CashPosted(UUID eventId, String account, long amount, Instant occurredAt) {}
```

`ledger/LedgerService.java` (public API: `post` + `balanceOf`; idempotent; one balanced transaction; publishes a signed-delta `CashPosted` per account):

```java
package io.github.ajayaj724.tradecore.ledger;

import io.github.ajayaj724.tradecore.shared.CashPosted;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Double-entry cash ledger. Cash for an account is the sum of its postings — never a mutable column. */
@Service
public class LedgerService {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    LedgerService(JdbcClient jdbc, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void post(TradeExecuted trade) {
        if (alreadyProcessed(trade.eventId())) {
            return;
        }
        long notional = trade.price() * trade.quantity();
        UUID txn = UUID.randomUUID();
        insertPosting(txn, trade.buyerAccount(), -notional);
        insertPosting(txn, trade.sellerAccount(), notional);
        jdbc.sql("insert into ledger.processed_event (event_id, processed_at) values (:e, :t)")
                .param("e", trade.eventId())
                .param("t", OffsetDateTime.now(clock))
                .update();
        events.publishEvent(new CashPosted(UUID.randomUUID(), trade.buyerAccount(), -notional, clock.instant()));
        events.publishEvent(new CashPosted(UUID.randomUUID(), trade.sellerAccount(), notional, clock.instant()));
    }

    public long balanceOf(String account) {
        return jdbc.sql("select coalesce(sum(amount), 0) from ledger.posting where account = :a")
                .param("a", account)
                .query(Long.class)
                .single();
    }

    private void insertPosting(UUID txn, String account, long amount) {
        jdbc.sql("insert into ledger.posting (txn_id, account, amount, kind) values (:t, :a, :amt, 'TRADE')")
                .param("t", txn)
                .param("a", account)
                .param("amt", amount)
                .update();
    }

    private boolean alreadyProcessed(UUID eventId) {
        return jdbc.sql("select count(*) from ledger.processed_event where event_id = :e")
                        .param("e", eventId)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
```

`ledger/LedgerListener.java`:

```java
package io.github.ajayaj724.tradecore.ledger;

import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class LedgerListener {

    private final LedgerService ledger;

    LedgerListener(LedgerService ledger) {
        this.ledger = ledger;
    }

    @ApplicationModuleListener
    void on(TradeExecuted trade) {
        ledger.post(trade);
    }
}
```

- [ ] **Step 5: Run it — verify green**

Run: `mvn -q -Dtest=LedgerServiceIT test`
Expected: PASS (all 3).

- [ ] **Step 6: Full gate + commit**

Run the `tradecore-quality-gate` skill (`ModularityTests`: `ledger → shared` only).

```bash
git add src/main/resources/db/migration/V6__ledger.sql \
  src/main/java/io/github/ajayaj724/tradecore/shared/CashPosted.java \
  src/main/java/io/github/ajayaj724/tradecore/ledger/ \
  src/test/java/io/github/ajayaj724/tradecore/ledger/LedgerServiceIT.java
git commit -m "feat: ledger module — double-entry cash, opening seed, CashPosted events"
```

---

### Task 3: `risk` cash rewire — `available = settled − holds`

Replace the seeded `available_cash` with a ledger-fed `settled_cash` read-model plus risk-owned `cash_hold`s; `risk.check` takes an `orderId` and reads `settled − Σ holds`; `OrderService` passes the order id.

**Files:**
- Create: `src/main/resources/db/migration/V7__risk_cash_rewire.sql`
- Modify: `src/main/java/io/github/ajayaj724/tradecore/risk/RiskService.java`
- Modify: `src/main/java/io/github/ajayaj724/tradecore/orders/OrderService.java` (pass `orderId`)
- Modify: `src/test/java/io/github/ajayaj724/tradecore/risk/RiskServiceIT.java` (new model + `orderId`)

**Interfaces:**
- Produces: `RiskService.check(long orderId, String account, Side side, String symbol, long price, long quantity)` — BUY approval inserts a `cash_hold` keyed by `orderId`; check uses `settled_cash − Σ (unit_price × remaining_qty)`.

- [ ] **Step 1: V7 migration**

`V7__risk_cash_rewire.sql`:

```sql
DROP TABLE risk.available_cash;   -- replaced by settled_cash + cash_hold

CREATE TABLE risk.settled_cash (
    account TEXT PRIMARY KEY,
    amount  BIGINT NOT NULL
);

CREATE TABLE risk.cash_hold (
    order_id      BIGINT PRIMARY KEY,
    account       TEXT   NOT NULL,
    unit_price    BIGINT NOT NULL,
    remaining_qty BIGINT NOT NULL
);

CREATE TABLE risk.processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- Seeded to match ledger openings; reconciliation guards they stay equal.
INSERT INTO risk.settled_cash (account, amount) VALUES
    ('trader1', 100000000),
    ('trader2', 100000000);
```

- [ ] **Step 2: Rewrite `RiskServiceIT` for the new model (test first)**

Replace the cash tests; the sell test is unchanged except it needs an `orderId`:

```java
    private long available(String account) {
        Long settled = jdbc.sql("select amount from risk.settled_cash where account = :a")
                .param("a", account)
                .query(Long.class)
                .single();
        Long held = jdbc.sql(
                        "select coalesce(sum(unit_price * remaining_qty), 0) from risk.cash_hold where account = :a")
                .param("a", account)
                .query(Long.class)
                .single();
        return settled - held;
    }

    @Test
    void approvesBuyWithinCashAndHolds() {
        long before = available("trader1");
        RiskDecision decision = risk.check(9001L, "trader1", Side.BUY, "ACME", 100L, 10L); // holds 1000

        assertThat(decision).isInstanceOf(RiskDecision.Approved.class);
        assertThat(available("trader1")).isEqualTo(before - 1000L); // settled unchanged; hold reduces available
    }

    @Test
    void rejectsBuyWhenAvailableInsufficient() {
        RiskDecision decision = risk.check(9002L, "trader2", Side.BUY, "ACME", 100000000L, 1000L);
        assertThat(decision).isInstanceOf(RiskDecision.Rejected.class);
    }

    @Test
    void rejectsSellWhenHoldingsInsufficient() {
        RiskDecision decision = risk.check(9003L, "trader1", Side.SELL, "ACME", 10000L, 999999L);
        assertThat(decision).isInstanceOf(RiskDecision.Rejected.class);
    }
```

(Delete the old `cash(...)` helper and the three old cash test bodies. Keep the class/imports; add `orderId` to any remaining call.)

- [ ] **Step 3: Run it — verify it fails**

Run: `mvn -q -Dtest=RiskServiceIT test`
Expected: FAIL — `check(...)` has no `orderId` overload; `risk.available_cash` gone.

- [ ] **Step 4: Rewrite `RiskService.check` + `reserveCash`**

Change the signature and the cash path (the `reserveHoldings` method stays exactly as-is):

```java
    @Transactional
    public RiskDecision check(long orderId, String account, Side side, String symbol, long price, long quantity) {
        return side == Side.BUY
                ? reserveCash(orderId, account, price, quantity)
                : reserveHoldings(account, symbol, quantity);
    }

    private RiskDecision reserveCash(long orderId, String account, long unitPrice, long quantity) {
        Long settled = jdbc.sql("select amount from risk.settled_cash where account = :a for update")
                .param("a", account)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (settled == null) {
            return new RiskDecision.Rejected("insufficient cash");
        }
        long held = jdbc.sql(
                        "select coalesce(sum(unit_price * remaining_qty), 0) from risk.cash_hold where account = :a")
                .param("a", account)
                .query(Long.class)
                .single();
        long cost = unitPrice * quantity;
        if (settled - held < cost) {
            return new RiskDecision.Rejected("insufficient cash");
        }
        jdbc.sql("insert into risk.cash_hold (order_id, account, unit_price, remaining_qty) values (:o, :a, :u, :q)")
                .param("o", orderId)
                .param("a", account)
                .param("u", unitPrice)
                .param("q", quantity)
                .update();
        return new RiskDecision.Approved();
    }
```

> **Race safety:** the `SELECT … FOR UPDATE` on the account's `settled_cash` row serializes concurrent buys for that account — the second buy blocks until the first commits its hold, then re-reads and sums the now-larger held total.

- [ ] **Step 5: Pass `orderId` from `OrderService`**

`OrderService.java:58` — the created order's id is already available (line 55). Change the risk call:

```java
        RiskDecision decision =
                risk.check(Objects.requireNonNull(created.id()), account, cmd.side(), cmd.symbol(), cmd.price(), cmd.quantity());
```

- [ ] **Step 6: Run it — verify green**

Run: `mvn -q -Dtest=RiskServiceIT test`
Expected: PASS (all 3).

- [ ] **Step 7: Full gate + commit**

Run the `tradecore-quality-gate` skill. `OrderSubmissionIT` still passes (an over-cash buy is rejected because `available < cost`; a small buy is approved and places a hold).

```bash
git add src/main/resources/db/migration/V7__risk_cash_rewire.sql \
  src/main/java/io/github/ajayaj724/tradecore/risk/RiskService.java \
  src/main/java/io/github/ajayaj724/tradecore/orders/OrderService.java \
  src/test/java/io/github/ajayaj724/tradecore/risk/RiskServiceIT.java
git commit -m "feat: risk cash rewire — available = settled(ledger-fed) - holds"
```

---

### Task 4: `risk` listeners — settled from `CashPosted`, hold release from `TradeExecuted`

Two idempotent listeners keep the read-model live: `CashPosted` increments `settled_cash`; `TradeExecuted` releases the filled order's hold.

**Files:**
- Modify: `src/main/java/io/github/ajayaj724/tradecore/risk/RiskService.java` (add `applyCashPosted`, `releaseHold`)
- Create: `src/main/java/io/github/ajayaj724/tradecore/risk/RiskProjectionListener.java`
- Create: `src/test/java/io/github/ajayaj724/tradecore/risk/RiskProjectionIT.java`

**Interfaces:**
- Consumes: `shared.CashPosted`, `shared.TradeExecuted`.
- Produces: `RiskService.applyCashPosted(CashPosted)` (settled += amount, deduped) ; `RiskService.releaseHold(TradeExecuted)` (hold `remaining_qty −= fillQty`, deduped).

- [ ] **Step 1: Write the projection IT first (incl. both duplicate-delivery cases)**

`RiskProjectionIT.java`:

```java
package io.github.ajayaj724.tradecore.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.CashPosted;
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
class RiskProjectionIT {

    private final RiskService risk;
    private final JdbcClient jdbc;

    @Autowired
    RiskProjectionIT(RiskService risk, JdbcClient jdbc) {
        this.risk = risk;
        this.jdbc = jdbc;
    }

    private long settled(String a) {
        return jdbc.sql("select amount from risk.settled_cash where account = :a")
                .param("a", a)
                .query(Long.class)
                .single();
    }

    private long holdQty(long orderId) {
        return jdbc.sql("select coalesce(sum(remaining_qty), 0) from risk.cash_hold where order_id = :o")
                .param("o", orderId)
                .query(Long.class)
                .single();
    }

    @Test
    void cashPostedUpdatesSettledOnceUnderRedelivery() {
        long before = settled("trader1");
        CashPosted e = new CashPosted(UUID.randomUUID(), "trader1", -20000L, Instant.EPOCH);
        risk.applyCashPosted(e);
        risk.applyCashPosted(e); // duplicate delivery
        assertThat(settled("trader1")).isEqualTo(before - 20000L); // applied once
    }

    @Test
    void tradeReleasesHoldOnceUnderRedelivery() {
        risk.check(7001L, "trader1", Side.BUY, "ACME", 10000L, 5L); // hold 5 @ 10000
        TradeExecuted t = new TradeExecuted(
                UUID.randomUUID(), 7001L, 8001L, "trader1", "trader2", "ACME", 9000L, 5L, Instant.EPOCH);
        risk.releaseHold(t);
        risk.releaseHold(t); // duplicate delivery
        assertThat(holdQty(7001L)).isZero(); // fully released, once
    }
}
```

- [ ] **Step 2: Run it — verify it fails to compile**

Run: `mvn -q -Dtest=RiskProjectionIT test`
Expected: FAIL — `applyCashPosted` / `releaseHold` do not exist.

- [ ] **Step 3: Add the projection methods to `RiskService`**

Add imports `io.github.ajayaj724.tradecore.shared.CashPosted`, `io.github.ajayaj724.tradecore.shared.TradeExecuted`, `java.util.UUID`; add methods:

```java
    @Transactional
    public void applyCashPosted(CashPosted event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        jdbc.sql("update risk.settled_cash set amount = amount + :amt where account = :a")
                .param("amt", event.amount())
                .param("a", event.account())
                .update();
        markProcessed(event.eventId());
    }

    @Transactional
    public void releaseHold(TradeExecuted trade) {
        if (alreadyProcessed(trade.eventId())) {
            return;
        }
        jdbc.sql("update risk.cash_hold set remaining_qty = remaining_qty - :q where order_id = :o")
                .param("q", trade.quantity())
                .param("o", trade.buyOrderId())
                .update();
        jdbc.sql("delete from risk.cash_hold where order_id = :o and remaining_qty <= 0")
                .param("o", trade.buyOrderId())
                .update();
        markProcessed(trade.eventId());
    }

    private boolean alreadyProcessed(UUID eventId) {
        return jdbc.sql("select count(*) from risk.processed_event where event_id = :e")
                        .param("e", eventId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private void markProcessed(UUID eventId) {
        jdbc.sql("insert into risk.processed_event (event_id, processed_at) values (:e, :t)")
                .param("e", eventId)
                .param("t", OffsetDateTime.now(clock))
                .update();
    }
```

Add the constructor dependency: `RiskService` needs a `Clock` now (for `markProcessed`). Add `private final Clock clock;`, add it to the constructor params and assignment (import `java.time.Clock`, `java.time.OffsetDateTime`).

- [ ] **Step 4: Add the listener component**

`risk/RiskProjectionListener.java`:

```java
package io.github.ajayaj724.tradecore.risk;

import io.github.ajayaj724.tradecore.shared.CashPosted;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class RiskProjectionListener {

    private final RiskService risk;

    RiskProjectionListener(RiskService risk) {
        this.risk = risk;
    }

    @ApplicationModuleListener
    void onCashPosted(CashPosted event) {
        risk.applyCashPosted(event);
    }

    @ApplicationModuleListener
    void onTradeExecuted(TradeExecuted trade) {
        risk.releaseHold(trade);
    }
}
```

- [ ] **Step 5: Run it — verify green**

Run: `mvn -q -Dtest=RiskProjectionIT test`
Expected: PASS (both).

- [ ] **Step 6: Full gate + commit**

Run the `tradecore-quality-gate` skill. Three new listeners across the slice (`ledger`, `risk`×2) now all have duplicate-delivery tests.

```bash
git add src/main/java/io/github/ajayaj724/tradecore/risk/RiskService.java \
  src/main/java/io/github/ajayaj724/tradecore/risk/RiskProjectionListener.java \
  src/test/java/io/github/ajayaj724/tradecore/risk/RiskProjectionIT.java
git commit -m "feat: risk projection listeners — settled from CashPosted, hold release from TradeExecuted"
```

---

### Task 5: Integration proof — a fill moves real cash end-to-end

The headline: a crossing fill posts balanced cash, `risk.settled_cash` tracks `ledger`, and the over-reservation refund lands in available cash.

**Files:**
- Create: `src/test/java/io/github/ajayaj724/tradecore/orders/CashSettlementEndToEndIT.java`

**Interfaces:**
- Consumes: the full app; MockMvc; `await()` for the async cash flow.

- [ ] **Step 1: Write the end-to-end IT**

`CashSettlementEndToEndIT.java` — trader2 rests a sell **below** trader1's buy limit, so the fill prints at the maker price and the over-reservation refunds:

```java
package io.github.ajayaj724.tradecore.orders;

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
class CashSettlementEndToEndIT {

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    @Autowired
    CashSettlementEndToEndIT(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private static RequestPostProcessor trader(String u) {
        return jwt().jwt(j -> j.claim("preferred_username", u)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    private void submit(String user, String side, long price, String key) throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(trader(user))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"" + side + "\",\"price\":" + price + ",\"quantity\":5}"))
                .andExpect(status().isCreated());
    }

    private long ledgerBalance(String a) {
        return jdbc.sql("select coalesce(sum(amount),0) from ledger.posting where account = :a")
                .param("a", a)
                .query(Long.class)
                .single();
    }

    private long available(String a) {
        long settled = jdbc.sql("select amount from risk.settled_cash where account = :a")
                .param("a", a)
                .query(Long.class)
                .single();
        long held = jdbc.sql("select coalesce(sum(unit_price*remaining_qty),0) from risk.cash_hold where account = :a")
                .param("a", a)
                .query(Long.class)
                .single();
        return settled - held;
    }

    @Test
    void fillSettlesCashAndRefundsOverReservation() throws Exception {
        submit("trader2", "SELL", 9000L, "cash-sell");  // maker rests @ 90.00
        submit("trader1", "BUY", 10000L, "cash-buy");   // taker limit 100.00 -> fills @ 90.00

        // settled: buyer -45000 (5 @ 9000), seller +45000; ledger == risk.settled_cash
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            org.assertj.core.api.Assertions.assertThat(ledgerBalance("trader1")).isEqualTo(100000000L - 45000L);
            org.assertj.core.api.Assertions.assertThat(ledgerBalance("trader2")).isEqualTo(100000000L + 45000L);
            org.assertj.core.api.Assertions.assertThat(
                            jdbc.sql("select amount from risk.settled_cash where account='trader1'")
                                    .query(Long.class)
                                    .single())
                    .isEqualTo(ledgerBalance("trader1"));
            // hold released -> available reflects only the 45000 actually spent, not the 50000 reserved
            org.assertj.core.api.Assertions.assertThat(available("trader1")).isEqualTo(100000000L - 45000L);
        });
    }
}
```

- [ ] **Step 2: Run it — verify green**

Run: `mvn -q -Dtest=CashSettlementEndToEndIT test` (Docker up)
Expected: PASS — the fill settles cash, `ledger ≡ risk.settled_cash`, and available reflects the maker-price spend (refund applied). If it times out, inspect `event_publication` for an incomplete listener; fix the listener, don't extend the window.

- [ ] **Step 3: Full gate + commit**

```bash
git add src/test/java/io/github/ajayaj724/tradecore/orders/CashSettlementEndToEndIT.java
git commit -m "test: end-to-end cash settlement + over-reservation refund proof"
```

---

### Task 6: Closeout — ADRs + gate

**Files:**
- Create: `docs/adr/0006-double-entry-cash-ledger.md`
- Create: `docs/adr/0007-available-cash-settled-minus-holds.md`

- [ ] **Step 1: Write the ADRs**

`0006-double-entry-cash-ledger.md` — postings-only (no mutable balance), every transaction balances to zero, cash = `Σ postings`, opening deposits as seeded balanced postings, direct buyer↔seller transfer (fees/clearing deferred). Follow the `0001` format.

`0007-available-cash-settled-minus-holds.md` — `available = settled(ledger-fed) − risk-owned holds`; the buy-reserves-at-limit / settles-at-maker over-reservation refund; the documented eventual-consistency window between the `CashPosted` (settled) and `TradeExecuted` (hold-release) listeners, with reconciliation as the backstop.

- [ ] **Step 2: Final whole-suite gate + commit**

Run the `tradecore-quality-gate` skill — full `mvn verify`, all green.

```bash
git add docs/adr/0006-double-entry-cash-ledger.md docs/adr/0007-available-cash-settled-minus-holds.md
git commit -m "docs: ADRs for double-entry ledger and settled-minus-holds cash model"
```

- [ ] **Step 3: Definition of done — verify each**

- [ ] A fill posts a balanced double-entry transaction; buyer settled down, seller up by the fill notional (`LedgerServiceIT`, `CashSettlementEndToEndIT`).
- [ ] `risk` buy check runs off `settled − holds`; over-reservation refund exercised (`CashSettlementEndToEndIT`).
- [ ] All three new listeners have duplicate-delivery tests (`LedgerServiceIT`, `RiskProjectionIT` ×2).
- [ ] `ledger.posting` for an account == `risk.settled_cash[account]` after a fill (reconciliation-lite).
- [ ] `ApplicationModules.verify()` green; JaCoCo ≥ 80%; two ADRs written; full `mvn verify` green.

---

## Self-Review (done at authoring time)

**Spec coverage:** every §1.1 item maps to a task — ledger double-entry (Task 2), enriched `TradeExecuted` (Task 1), `settled − holds` rewire (Task 3), three idempotent listeners (Tasks 2/4), reconciliation-lite + refund proof (Task 5), ADRs (Task 6). Holdings side left untouched (2B), as scoped.

**Placeholder scan:** no TBD/TODO; every step has real code or an exact command. ADR bodies are described with required content (author writes prose in the 0001 format) — consistent with how 1B's ADR step was specified.

**Type consistency:** `TradeExecuted`'s new `(buyerAccount, sellerAccount)` positions are used identically at all four construction sites and in `LedgerService.post` / `RiskService.releaseHold`. `CashPosted(eventId, account, amount, occurredAt)` fields match between Task 2 (publish) and Task 4 (consume). `risk.check`'s new `orderId`-first signature matches its `OrderService` caller and all `RiskServiceIT`/`RiskProjectionIT` call sites. Migrations ascend V5 → V6 → V7 by task order.
