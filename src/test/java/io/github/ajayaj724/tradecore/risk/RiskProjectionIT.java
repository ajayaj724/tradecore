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
