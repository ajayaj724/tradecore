package io.github.ajayaj724.tradecore.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class MarketDataServiceIT {

    private final MarketDataService md;

    @Autowired
    MarketDataServiceIT(MarketDataService md) {
        this.md = md;
    }

    private static TradeExecuted trade(String symbol, long price) {
        return new TradeExecuted(UUID.randomUUID(), 1L, 2L, "trader1", "trader2", symbol, price, 5L, Instant.EPOCH);
    }

    @Test
    void tradeUpdatesLastPrice() {
        md.onTrade(trade("ACME", 12345L));
        assertThat(md.lastPrice("ACME")).isEqualTo(12345L);
    }

    @Test
    void redeliveryOfSameTradeIsANoOp() {
        TradeExecuted t = trade("ACME", 11111L);
        md.onTrade(t);
        md.onTrade(t);
        assertThat(md.lastPrice("ACME")).isEqualTo(11111L);
    }
}
