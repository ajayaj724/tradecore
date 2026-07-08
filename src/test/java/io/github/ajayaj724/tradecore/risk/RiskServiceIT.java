package io.github.ajayaj724.tradecore.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.Side;
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
}
