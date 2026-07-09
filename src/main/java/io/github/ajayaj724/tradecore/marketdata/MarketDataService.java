package io.github.ajayaj724.tradecore.marketdata;

import io.github.ajayaj724.tradecore.shared.PriceUpdated;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Last prices: seeded openings, then updated from each trade's fill price. */
@Service
public class MarketDataService {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    MarketDataService(JdbcClient jdbc, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void onTrade(TradeExecuted trade) {
        if (alreadyProcessed(trade.eventId())) {
            return;
        }
        jdbc.sql("insert into marketdata.last_price (symbol, price) values (:s, :p)"
                        + " on conflict (symbol) do update set price = :p")
                .param("s", trade.symbol())
                .param("p", trade.price())
                .update();
        jdbc.sql("insert into marketdata.processed_event (event_id, processed_at) values (:e, :t)")
                .param("e", trade.eventId())
                .param("t", OffsetDateTime.now(clock))
                .update();
        events.publishEvent(new PriceUpdated(UUID.randomUUID(), trade.symbol(), trade.price(), clock.instant()));
    }

    @Transactional
    public void applyExternalPrice(String symbol, long price, Instant observedAt) {
        // Publish-on-change: the scheduled feed re-applies every symbol each poll; emitting
        // PriceUpdated only when the stored price actually moves keeps the event-publication and
        // downstream processed-event tables from growing unboundedly. The conditional upsert does
        // the compare-and-set atomically — RETURNING yields a row only on insert or a real change.
        boolean changed = jdbc.sql("insert into marketdata.last_price (symbol, price) values (:s, :p)"
                        + " on conflict (symbol) do update set price = :p"
                        + " where marketdata.last_price.price <> excluded.price"
                        + " returning price")
                .param("s", symbol)
                .param("p", price)
                .query(Long.class)
                .optional()
                .isPresent();
        if (changed) {
            events.publishEvent(new PriceUpdated(UUID.randomUUID(), symbol, price, observedAt));
        }
    }

    public long lastPrice(String symbol) {
        return jdbc.sql("select price from marketdata.last_price where symbol = :s")
                .param("s", symbol)
                .query(Long.class)
                .single();
    }

    private boolean alreadyProcessed(UUID eventId) {
        return jdbc.sql("select count(*) from marketdata.processed_event where event_id = :e")
                        .param("e", eventId)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
