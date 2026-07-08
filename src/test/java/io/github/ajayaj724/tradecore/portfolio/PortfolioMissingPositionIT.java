package io.github.ajayaj724.tradecore.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class PortfolioMissingPositionIT {

    private final PortfolioService portfolio;

    @Autowired
    PortfolioMissingPositionIT(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @Test
    void positionQtyReturnsZeroWhenNoPositionRow() {
        assertThat(portfolio.positionQty("pf-missing", "NONE")).isZero();
    }

    @Test
    void realizedPnlReturnsZeroWhenNoPositionRow() {
        assertThat(portfolio.realizedPnl("pf-missing", "NONE")).isZero();
    }

    @Test
    void unrealizedPnlReturnsZeroWhenNoPositionRow() {
        assertThat(portfolio.unrealizedPnl("pf-missing", "NONE")).isZero();
    }
}
