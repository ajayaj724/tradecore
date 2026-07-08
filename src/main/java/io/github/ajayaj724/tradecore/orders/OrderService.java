package io.github.ajayaj724.tradecore.orders;

import io.github.ajayaj724.tradecore.risk.RiskDecision;
import io.github.ajayaj724.tradecore.risk.RiskService;
import io.github.ajayaj724.tradecore.shared.OrderAccepted;
import io.github.ajayaj724.tradecore.shared.OrderRejected;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderService {

    private final OrderRepository orders;
    private final AuditRepository audit;
    private final InstrumentRepository instruments;
    private final RiskService risk;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OrderService(
            OrderRepository orders,
            AuditRepository audit,
            InstrumentRepository instruments,
            RiskService risk,
            JdbcClient jdbc,
            ApplicationEventPublisher events,
            Clock clock) {
        this.orders = orders;
        this.audit = audit;
        this.instruments = instruments;
        this.risk = risk;
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    Order submit(String account, String principal, SubmitOrderCommand cmd) {
        Order replayed = replayIfDuplicate(cmd.idempotencyKey());
        if (replayed != null) {
            return replayed;
        }
        if (!instruments.existsById(cmd.symbol())) {
            throw new UnknownSymbolException(cmd.symbol());
        }
        Order created = orders.save(Order.newOrder(account, cmd.symbol(), cmd.side(), cmd.price(), cmd.quantity()));
        record(created, "SUBMITTED", principal);
        rememberKey(cmd.idempotencyKey(), Objects.requireNonNull(created.id()));
        RiskDecision decision = risk.check(account, cmd.side(), cmd.symbol(), cmd.price(), cmd.quantity());
        return switch (decision) {
            case RiskDecision.Rejected r -> reject(created, r.reason(), principal);
            case RiskDecision.Approved ignored -> accept(created, principal);
        };
    }

    private Order accept(Order order, String principal) {
        Order accepted = orders.save(order.accepted());
        record(accepted, "ACCEPTED", principal);
        events.publishEvent(new OrderAccepted(
                UUID.randomUUID(),
                Objects.requireNonNull(accepted.id()),
                accepted.account(),
                accepted.symbol(),
                accepted.side(),
                accepted.price(),
                accepted.quantity(),
                clock.instant()));
        return accepted;
    }

    private Order reject(Order order, String reason, String principal) {
        Order rejected = orders.save(order.rejected(reason));
        record(rejected, "REJECTED", principal);
        events.publishEvent(new OrderRejected(
                UUID.randomUUID(),
                Objects.requireNonNull(rejected.id()),
                rejected.account(),
                rejected.symbol(),
                rejected.side(),
                rejected.price(),
                rejected.quantity(),
                reason,
                clock.instant()));
        return rejected;
    }

    private @Nullable Order replayIfDuplicate(String key) {
        Long orderId = jdbc.sql("select order_id from orders.idempotency where key = :k")
                .param("k", key)
                .query(Long.class)
                .optional()
                .orElse(null);
        return orderId == null ? null : orders.findById(orderId).orElseThrow();
    }

    private void rememberKey(String key, long orderId) {
        jdbc.sql("insert into orders.idempotency (key, order_id, created_at) values (:k, :o, :t)")
                .param("k", key)
                .param("o", orderId)
                .param("t", OffsetDateTime.now(clock))
                .update();
    }

    private void record(Order order, String action, String principal) {
        audit.save(new AuditRecord(
                null, Objects.requireNonNull(order.id()), order.account(), action, principal, clock.instant(), null));
    }
}
