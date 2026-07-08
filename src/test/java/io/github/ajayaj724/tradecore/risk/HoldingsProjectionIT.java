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
        risk.applyHoldingsPosted(e);
        assertThat(settled("trader1", "ACME")).isEqualTo(before - 10L);
    }

    @Test
    void tradeReleasesSellerHoldOnceUnderRedelivery() {
        risk.check(7101L, "trader1", Side.SELL, "ACME", 10000L, 5L);
        TradeExecuted t = new TradeExecuted(
                UUID.randomUUID(), 8101L, 7101L, "trader2", "trader1", "ACME", 10000L, 5L, Instant.EPOCH);
        risk.releaseHold(t);
        risk.releaseHold(t);
        assertThat(holdQty(7101L)).isZero();
    }
}
