package io.github.ajayaj724.tradecore.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.OrderAccepted;
import io.github.ajayaj724.tradecore.shared.OrderCancelRequested;
import io.github.ajayaj724.tradecore.shared.Side;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfig.class)
class EmbeddedMatchingVenueIT {

    private final ExecutionVenue venue;
    private final JdbcClient jdbc;

    @Autowired
    EmbeddedMatchingVenueIT(ExecutionVenue venue, JdbcClient jdbc) {
        this.venue = venue;
        this.jdbc = jdbc;
    }

    // Distinct symbols per method: the engine is a shared in-memory bean across the suite, so each
    // test uses its own symbol (unused elsewhere) to keep its book isolated.
    private static OrderAccepted accepted(long id, Side side, long price, long qty, String symbol) {
        return new OrderAccepted(UUID.randomUUID(), id, "trader1", symbol, side, price, qty, Instant.EPOCH);
    }

    private static OrderCancelRequested cancelRequest(long id, Side side, String symbol) {
        return new OrderCancelRequested(UUID.randomUUID(), id, "trader1", symbol, side, Instant.EPOCH);
    }

    @Test
    void crossingOrdersProduceATrade() {
        venue.submit(accepted(101L, Side.SELL, 10000L, 5L, "ZZZ")); // rests
        List<TradeExecuted> trades = venue.submit(accepted(102L, Side.BUY, 10000L, 5L, "ZZZ"));

        assertThat(trades).hasSize(1);
        assertThat(trades.getFirst().buyOrderId()).isEqualTo(102L);
        assertThat(trades.getFirst().sellOrderId()).isEqualTo(101L);
        assertThat(trades.getFirst().quantity()).isEqualTo(5L);
        assertThat(trades.getFirst().buyerAccount()).isEqualTo("trader1");
        assertThat(trades.getFirst().sellerAccount()).isEqualTo("trader1");
    }

    @Test
    void redeliveryOfSameEventIsANoOp() {
        OrderAccepted sell = accepted(201L, Side.SELL, 10000L, 5L, "YYY");
        venue.submit(sell);
        List<TradeExecuted> second = venue.submit(sell); // same eventId → deduped

        assertThat(second).isEmpty();
        Long count = jdbc.sql("select count(*) from execution.processed_event where event_id = :id")
                .param("id", sell.eventId())
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void cancelRemovesTheRestingRemainderFromTheBook() {
        venue.submit(accepted(301L, Side.SELL, 10000L, 5L, "CXA")); // rests as an ask

        venue.cancel(cancelRequest(301L, Side.SELL, "CXA"));

        // The resting ask is gone, so a would-be crossing buy finds nothing to fill.
        List<TradeExecuted> trades = venue.submit(accepted(302L, Side.BUY, 10000L, 5L, "CXA"));
        assertThat(trades).isEmpty();
    }

    @Test
    void redeliveryOfSameCancelRequestIsANoOp() {
        venue.submit(accepted(311L, Side.SELL, 10000L, 5L, "CXB"));
        OrderCancelRequested request = cancelRequest(311L, Side.SELL, "CXB");

        venue.cancel(request);
        venue.cancel(request); // same eventId → deduped

        Long count = jdbc.sql("select count(*) from execution.processed_event where event_id = :id")
                .param("id", request.eventId())
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void anAcceptThatArrivesAfterItsCancelDoesNotRest() {
        // Cancel is processed before the accept (the cancel-before-accept race): it leaves a marker.
        venue.cancel(cancelRequest(321L, Side.SELL, "CXC"));
        venue.submit(accepted(321L, Side.SELL, 10000L, 5L, "CXC")); // late accept must be skipped

        // Since 321 never rested, a crossing buy produces no trade.
        List<TradeExecuted> trades = venue.submit(accepted(322L, Side.BUY, 10000L, 5L, "CXC"));
        assertThat(trades).isEmpty();
    }
}
