package io.github.ajayaj724.tradecore.orders;

import io.github.ajayaj724.tradecore.shared.Side;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "orders", name = "trade_order")
record Order(
        @Id @Nullable Long id,
        String account,
        String symbol,
        Side side,
        long price,
        long quantity,
        long filledQty,
        OrderStatus status,
        @Nullable String rejectReason,
        @Version @Nullable Long version) {

    static Order newOrder(String account, String symbol, Side side, long price, long quantity) {
        return new Order(null, account, symbol, side, price, quantity, 0, OrderStatus.NEW, null, null);
    }

    Order accepted() {
        return new Order(id, account, symbol, side, price, quantity, filledQty, OrderStatus.ACCEPTED, null, version);
    }

    Order rejected(String reason) {
        return new Order(id, account, symbol, side, price, quantity, filledQty, OrderStatus.REJECTED, reason, version);
    }

    Order withFill(long addQty) {
        long total = filledQty + addQty;
        OrderStatus next = total >= quantity ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        return new Order(id, account, symbol, side, price, quantity, total, next, null, version);
    }

    Order cancelled() {
        return new Order(id, account, symbol, side, price, quantity, filledQty, OrderStatus.CANCELLED, null, version);
    }
}
