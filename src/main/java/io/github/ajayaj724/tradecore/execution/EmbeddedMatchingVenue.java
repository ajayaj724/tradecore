package io.github.ajayaj724.tradecore.execution;

import io.github.ajayaj724.tradecore.execution.engine.Fill;
import io.github.ajayaj724.tradecore.execution.engine.MatchingEngine;
import io.github.ajayaj724.tradecore.execution.engine.Side;
import io.github.ajayaj724.tradecore.shared.OrderAccepted;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Embedded {@link ExecutionVenue} adapter: consumes {@code OrderAccepted}, drives the framework-free
 * engine, and publishes one {@code TradeExecuted} per fill. Idempotent — a redelivered event is a no-op.
 */
@Component
class EmbeddedMatchingVenue implements ExecutionVenue {

    private final MatchingEngine engine = new MatchingEngine();
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    EmbeddedMatchingVenue(JdbcClient jdbc, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(OrderAccepted order) {
        submit(order);
    }

    @Override
    public List<TradeExecuted> submit(OrderAccepted order) {
        if (alreadyProcessed(order.eventId())) {
            return List.of();
        }
        List<Fill> fills = engine.submit(
                order.symbol(), order.orderId(), engineSide(order.side()), order.price(), order.quantity());
        markProcessed(order.eventId());
        List<TradeExecuted> trades = new ArrayList<>();
        for (Fill f : fills) {
            TradeExecuted trade = new TradeExecuted(
                    UUID.randomUUID(),
                    f.buyOrderId(),
                    f.sellOrderId(),
                    order.symbol(),
                    f.price(),
                    f.quantity(),
                    clock.instant());
            events.publishEvent(trade);
            trades.add(trade);
        }
        return trades;
    }

    private static Side engineSide(io.github.ajayaj724.tradecore.shared.Side side) {
        return side == io.github.ajayaj724.tradecore.shared.Side.BUY ? Side.BUY : Side.SELL;
    }

    private boolean alreadyProcessed(UUID eventId) {
        return jdbc.sql("select count(*) from execution.processed_event where event_id = :id")
                        .param("id", eventId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private void markProcessed(UUID eventId) {
        jdbc.sql("insert into execution.processed_event (event_id, processed_at) values (:id, :t)")
                .param("id", eventId)
                .param("t", OffsetDateTime.now(clock))
                .update();
    }
}
