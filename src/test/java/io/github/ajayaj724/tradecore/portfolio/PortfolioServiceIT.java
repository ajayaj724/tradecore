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
        portfolio.onTrade(trade("alice", "zoe", 10000L, 5L));

        assertThat(portfolio.positionQty("alice", "PF")).isEqualTo(5L);
        portfolio.onPrice(new PriceUpdated(UUID.randomUUID(), "PF", 12000L, Instant.EPOCH));
        assertThat(portfolio.unrealizedPnl("alice", "PF")).isEqualTo(10000L); // 12000*5 - 10000*5
    }

    @Test
    void sellRealizesIntegerPnlAndRoundTripConserves() {
        portfolio.onTrade(trade("bob", "zoe", 10000L, 5L)); // buys 5 @ 100.00 (cost 50000)
        portfolio.onTrade(trade("carol", "bob", 12000L, 5L)); // sells 5 @ 120.00 (proceeds 60000)

        assertThat(portfolio.positionQty("bob", "PF")).isZero();
        assertThat(portfolio.realizedPnl("bob", "PF")).isEqualTo(10000L); // 60000 - 50000
    }

    @Test
    void reapplyingSameTradeIsANoOp() {
        TradeExecuted t = trade("dave", "zoe", 10000L, 4L);
        portfolio.onTrade(t);
        portfolio.onTrade(t);
        assertThat(portfolio.positionQty("dave", "PF")).isEqualTo(4L);
    }
}
