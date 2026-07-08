package io.github.ajayaj724.tradecore.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfig.class)
class RiskSettledGettersIT {

    private final RiskService risk;
    private final JdbcClient jdbc;

    @Autowired
    RiskSettledGettersIT(RiskService risk, JdbcClient jdbc) {
        this.risk = risk;
        this.jdbc = jdbc;
    }

    // trader1/ACME are shared fixtures mutated by other ITs (e.g. LedgerServiceIT) sharing this
    // Spring test context; assert against the live row rather than the pristine V7/V10 seed value.
    private long rawSettledCash(String account) {
        return jdbc.sql("select amount from risk.settled_cash where account = :a")
                .param("a", account)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    private long rawSettledHoldings(String account, String symbol) {
        return jdbc.sql("select qty from risk.settled_holdings where account = :a and symbol = :s")
                .param("a", account)
                .param("s", symbol)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    @Test
    void exposesSeededSettledCash() {
        assertThat(risk.settledCash("trader1")).isPositive().isEqualTo(rawSettledCash("trader1"));
    }

    @Test
    void exposesSeededSettledHoldings() {
        assertThat(risk.settledHoldings("trader1", "ACME"))
                .isPositive()
                .isEqualTo(rawSettledHoldings("trader1", "ACME"));
    }

    @Test
    void returnsZeroForUnknownAccountOrSymbol() {
        assertThat(risk.settledCash("nobody")).isEqualTo(0L);
        assertThat(risk.settledHoldings("trader1", "NOSUCH")).isEqualTo(0L);
    }
}
