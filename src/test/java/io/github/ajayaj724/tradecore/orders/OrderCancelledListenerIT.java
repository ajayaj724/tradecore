package io.github.ajayaj724.tradecore.orders;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.OrderCancelled;
import io.github.ajayaj724.tradecore.shared.Side;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// Insert orders directly (no submit, no shared engine) to isolate applyCancel under test.
@SpringBootTest
@Import(TestcontainersConfig.class)
class OrderCancelledListenerIT {

    private final OrderService service;
    private final OrderRepository orders;

    @Autowired
    OrderCancelledListenerIT(OrderService service, OrderRepository orders) {
        this.service = service;
        this.orders = orders;
    }

    private long insert(OrderStatus status, long filledQty) {
        Order o = new Order(null, "trader1", "ACME", Side.BUY, 10000L, 5L, filledQty, status, null, null);
        return Objects.requireNonNull(orders.save(o).id());
    }

    private static OrderCancelled cancelledFor(long id, long qty) {
        return new OrderCancelled(UUID.randomUUID(), id, "trader1", "ACME", Side.BUY, qty, Instant.EPOCH);
    }

    @Test
    void applyCancelMovesAWorkingOrderToCancelled() {
        long id = insert(OrderStatus.PARTIALLY_FILLED, 2L);

        service.applyCancel(cancelledFor(id, 3L));

        Order after = orders.findById(id).orElseThrow();
        assertThat(after.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(after.filledQty()).isEqualTo(2L); // fills already booked are kept
    }

    @Test
    void reapplyingTheSameCancelIsANoOp() {
        long id = insert(OrderStatus.ACCEPTED, 0L);
        OrderCancelled cancelled = cancelledFor(id, 5L);

        service.applyCancel(cancelled);
        service.applyCancel(cancelled); // duplicate delivery

        assertThat(orders.findById(id).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void applyCancelDoesNotResurrectAFilledOrder() {
        long id = insert(OrderStatus.FILLED, 5L); // fill won the race

        service.applyCancel(cancelledFor(id, 0L)); // stale cancel arrives afterwards

        assertThat(orders.findById(id).orElseThrow().status()).isEqualTo(OrderStatus.FILLED); // unchanged
    }
}
