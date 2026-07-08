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

    private long cash(String account) {
        return jdbc.sql("select amount from risk.available_cash where account = :a")
                .param("a", account)
                .query(Long.class)
                .single();
    }

    @Test
    void approvesBuyWithinCashAndReserves() {
        long before = cash("trader1");
        RiskDecision decision = risk.check("trader1", Side.BUY, "ACME", 100L, 10L); // 1000 paise

        assertThat(decision).isInstanceOf(RiskDecision.Approved.class);
        assertThat(cash("trader1")).isEqualTo(before - 1000L);
    }

    @Test
    void rejectsBuyWhenCashInsufficient() {
        long before = cash("trader2");
        RiskDecision decision = risk.check("trader2", Side.BUY, "ACME", 100000000L, 1000L); // 1e11 paise

        assertThat(decision).isInstanceOf(RiskDecision.Rejected.class);
        assertThat(cash("trader2")).isEqualTo(before); // untouched
    }

    @Test
    void rejectsSellWhenHoldingsInsufficient() {
        RiskDecision decision = risk.check("trader1", Side.SELL, "ACME", 10000L, 999999L);
        assertThat(decision).isInstanceOf(RiskDecision.Rejected.class);
    }
}
