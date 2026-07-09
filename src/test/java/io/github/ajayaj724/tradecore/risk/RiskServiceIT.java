package io.github.ajayaj724.tradecore.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.OrderCancelled;
import io.github.ajayaj724.tradecore.shared.Side;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfig.class)
class RiskServiceIT {

    private final RiskService risk;
    private final JdbcClient jdbc;

    @Autowired
    RiskServiceIT(RiskService risk, JdbcClient jdbc) {
        this.risk = risk;
        this.jdbc = jdbc;
    }

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

    private long availableHoldings(String account, String symbol) {
        Long settled = jdbc.sql("select qty from risk.settled_holdings where account = :a and symbol = :s")
                .param("a", account)
                .param("s", symbol)
                .query(Long.class)
                .single();
        Long held = jdbc.sql("select coalesce(sum(remaining_qty), 0) from risk.holdings_hold"
                        + " where account = :a and symbol = :s")
                .param("a", account)
                .param("s", symbol)
                .query(Long.class)
                .single();
        return settled - held;
    }

    @Test
    void releaseOnCancelCreditsBackReservedCashAndIsIdempotent() {
        long before = available("trader1");
        risk.check(9101L, "trader1", Side.BUY, "ACME", 100L, 10L); // holds 1000
        assertThat(available("trader1")).isEqualTo(before - 1000L);

        OrderCancelled cancelled =
                new OrderCancelled(UUID.randomUUID(), 9101L, "trader1", "ACME", Side.BUY, 10L, Instant.EPOCH);
        risk.releaseOnCancel(cancelled);
        assertThat(available("trader1")).isEqualTo(before); // hold freed

        risk.releaseOnCancel(cancelled); // duplicate delivery
        assertThat(available("trader1")).isEqualTo(before); // freed exactly once, not twice

        Long processed = jdbc.sql("select count(*) from risk.processed_event where event_id = :e")
                .param("e", cancelled.eventId())
                .query(Long.class)
                .single();
        assertThat(processed).isEqualTo(1L);
    }

    @Test
    void releaseOnCancelCreditsBackReservedHoldings() {
        long before = availableHoldings("trader2", "ACME");
        risk.check(9102L, "trader2", Side.SELL, "ACME", 10000L, 4L); // holds 4 shares
        assertThat(availableHoldings("trader2", "ACME")).isEqualTo(before - 4L);

        risk.releaseOnCancel(
                new OrderCancelled(UUID.randomUUID(), 9102L, "trader2", "ACME", Side.SELL, 4L, Instant.EPOCH));
        assertThat(availableHoldings("trader2", "ACME")).isEqualTo(before); // shares released
    }
}
