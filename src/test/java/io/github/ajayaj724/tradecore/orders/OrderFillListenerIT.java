package io.github.ajayaj724.tradecore.orders;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.Side;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class OrderFillListenerIT {

    private final OrderService service;
    private final OrderRepository orders;

    @Autowired
    OrderFillListenerIT(OrderService service, OrderRepository orders) {
        this.service = service;
        this.orders = orders;
    }

    // Insert ACCEPTED orders directly (no submit, so no OrderAccepted event and no shared engine) —
    // this isolates applyTrade under test. The full crossing pipeline is proven end-to-end in Task 8.
    private long insertAccepted(String account, Side side) {
        Order o = new Order(null, account, "ACME", side, 10000L, 5L, 0, OrderStatus.ACCEPTED, null, null);
        return Objects.requireNonNull(orders.save(o).id());
    }

    @Test
    void tradeAdvancesBothOrdersToFilled() {
        long sellId = insertAccepted("trader2", Side.SELL);
        long buyId = insertAccepted("trader1", Side.BUY);
        TradeExecuted trade = new TradeExecuted(
                UUID.randomUUID(), buyId, sellId, "trader1", "trader2", "ACME", 10000L, 5L, Instant.EPOCH);

        service.applyTrade(trade);

        assertThat(orders.findById(buyId).orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(orders.findById(sellId).orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(orders.findById(buyId).orElseThrow().filledQty()).isEqualTo(5L);
    }

    @Test
    void reapplyingSameTradeDoesNotDoubleCount() {
        long sellId = insertAccepted("trader2", Side.SELL);
        long buyId = insertAccepted("trader1", Side.BUY);
        TradeExecuted trade = new TradeExecuted(
                UUID.randomUUID(), buyId, sellId, "trader1", "trader2", "ACME", 10000L, 3L, Instant.EPOCH);

        service.applyTrade(trade);
        service.applyTrade(trade); // duplicate delivery

        assertThat(orders.findById(buyId).orElseThrow().filledQty()).isEqualTo(3L); // once, not 6
    }

    @Test
    void fillDeliveredAfterACancelBooksTheQuantityButKeepsCancelled() {
        // A market IOC (or a cancel) already finalised the buy as CANCELLED; a TradeExecuted delivered
        // afterwards must book the filled quantity without resurrecting the order.
        long sellId = insertAccepted("trader2", Side.SELL);
        Order cancelledBuy =
                new Order(null, "trader1", "ACME", Side.BUY, 10000L, 5L, 0, OrderStatus.CANCELLED, null, null);
        long buyId = Objects.requireNonNull(orders.save(cancelledBuy).id());
        TradeExecuted trade = new TradeExecuted(
                UUID.randomUUID(), buyId, sellId, "trader1", "trader2", "ACME", 10000L, 3L, Instant.EPOCH);

        service.applyTrade(trade);

        Order after = orders.findById(buyId).orElseThrow();
        assertThat(after.status()).isEqualTo(OrderStatus.CANCELLED); // not resurrected
        assertThat(after.filledQty()).isEqualTo(3L); // but the fill is booked
    }
}
