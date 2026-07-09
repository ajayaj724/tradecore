package io.github.ajayaj724.tradecore.execution;

import io.github.ajayaj724.tradecore.execution.engine.Fill;
import io.github.ajayaj724.tradecore.execution.engine.MatchingEngine;
import io.github.ajayaj724.tradecore.execution.engine.Side;
import io.github.ajayaj724.tradecore.shared.OrderAccepted;
import io.github.ajayaj724.tradecore.shared.OrderCancelRequested;
import io.github.ajayaj724.tradecore.shared.OrderCancelled;
import io.github.ajayaj724.tradecore.shared.OrderType;
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

    @ApplicationModuleListener
    void onCancel(OrderCancelRequested request) {
        cancel(request);
    }

    @Override
    public List<TradeExecuted> submit(OrderAccepted order) {
        if (alreadyProcessed(order.eventId())) {
            return List.of();
        }
        if (isCancelled(order.orderId())) {
            markProcessed(order.eventId()); // a cancel reached us first — do not rest this order
            return List.of();
        }
        rememberAccount(order.orderId(), order.account());
        boolean market = order.type() == OrderType.MARKET;
        Side side = engineSide(order.side());
        List<Fill> fills = market
                ? engine.submitIoc(order.symbol(), order.orderId(), side, order.price(), order.quantity())
                : engine.submit(order.symbol(), order.orderId(), side, order.price(), order.quantity());
        markProcessed(order.eventId());
        List<TradeExecuted> trades = new ArrayList<>();
        long filled = 0;
        for (Fill f : fills) {
            TradeExecuted trade = new TradeExecuted(
                    UUID.randomUUID(),
                    f.buyOrderId(),
                    f.sellOrderId(),
                    accountOf(f.buyOrderId()),
                    accountOf(f.sellOrderId()),
                    order.symbol(),
                    f.price(),
                    f.quantity(),
                    clock.instant());
            events.publishEvent(trade);
            trades.add(trade);
            filled += f.quantity();
        }
        if (market) {
            cancelRemainder(order, filled);
        }
        return trades;
    }

    /** A market order never rests: cancel the unfilled remainder so risk frees its reserved cap hold. */
    private void cancelRemainder(OrderAccepted order, long filled) {
        long remainder = order.quantity() - filled;
        if (remainder > 0) {
            events.publishEvent(new OrderCancelled(
                    UUID.randomUUID(),
                    order.orderId(),
                    order.account(),
                    order.symbol(),
                    order.side(),
                    remainder,
                    clock.instant()));
        }
    }

    /**
     * Remove an order's resting remainder from the book and answer with {@link OrderCancelled}. The
     * {@code cancelled_order} marker is written first so an OrderAccepted that has not yet been
     * processed (the cancel-before-accept race) will be refused a resting slot by {@link #submit}.
     */
    @Override
    public void cancel(OrderCancelRequested request) {
        if (alreadyProcessed(request.eventId())) {
            return;
        }
        markCancelled(request.orderId());
        long cancelledQty = engine.cancel(request.symbol(), request.orderId());
        markProcessed(request.eventId());
        events.publishEvent(new OrderCancelled(
                UUID.randomUUID(),
                request.orderId(),
                request.account(),
                request.symbol(),
                request.side(),
                cancelledQty,
                clock.instant()));
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

    private boolean isCancelled(long orderId) {
        return jdbc.sql("select count(*) from execution.cancelled_order where order_id = :o")
                        .param("o", orderId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private void markCancelled(long orderId) {
        jdbc.sql("insert into execution.cancelled_order (order_id, cancelled_at) values (:o, :t)"
                        + " on conflict (order_id) do nothing")
                .param("o", orderId)
                .param("t", OffsetDateTime.now(clock))
                .update();
    }

    private void rememberAccount(long orderId, String account) {
        jdbc.sql("insert into execution.order_account (order_id, account) values (:id, :a)"
                        + " on conflict (order_id) do nothing")
                .param("id", orderId)
                .param("a", account)
                .update();
    }

    private String accountOf(long orderId) {
        return jdbc.sql("select account from execution.order_account where order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }
}
