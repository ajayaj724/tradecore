package io.github.ajayaj724.tradecore.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

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

    @Test
    @Transactional
    void settledCashReturnsSeededAmount() {
        jdbc.sql("insert into risk.settled_cash (account, amount) values ('getters-test', 42)")
                .update();
        assertThat(risk.settledCash("getters-test")).isEqualTo(42L);
    }

    @Test
    @Transactional
    void settledHoldingsReturnsSeededQty() {
        jdbc.sql("insert into risk.settled_holdings (account, symbol, qty) values ('getters-test', 'ZZZZ', 7)")
                .update();
        assertThat(risk.settledHoldings("getters-test", "ZZZZ")).isEqualTo(7L);
    }

    @Test
    void returnsZeroForUnknownAccountOrSymbol() {
        assertThat(risk.settledCash("nobody")).isZero();
        assertThat(risk.settledHoldings("getters-test", "NOSUCH")).isZero();
    }
}
