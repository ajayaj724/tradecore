package io.github.ajayaj724.tradecore.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventContractsTest {

    @Test
    void tradeExecutedCarriesBothSides() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        TradeExecuted t = new TradeExecuted(id, 2L, 1L, "ACME", 10000L, 5L, Instant.EPOCH);
        assertThat(t.buyOrderId()).isEqualTo(2L);
        assertThat(t.sellOrderId()).isEqualTo(1L);
        assertThat(t.price()).isEqualTo(10000L);
    }

    @Test
    void orderAcceptedCarriesSideAndAccount() {
        OrderAccepted a =
                new OrderAccepted(UUID.randomUUID(), 7L, "trader1", "ACME", Side.BUY, 10000L, 5L, Instant.EPOCH);
        assertThat(a.side()).isEqualTo(Side.BUY);
        assertThat(a.account()).isEqualTo("trader1");
    }
}
