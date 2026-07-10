package io.github.ajayaj724.tradecore.orders;

import io.github.ajayaj724.tradecore.shared.OrderType;
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
        OrderType orderType,
        long price,
        long quantity,
        long filledQty,
        OrderStatus status,
        @Nullable String rejectReason,
        @Version @Nullable Long version) {

    static Order newOrder(String account, String symbol, Side side, OrderType type, long price, long quantity) {
        return new Order(null, account, symbol, side, type, price, quantity, 0, OrderStatus.NEW, null, null);
    }

    /** Accept at the effective price — the client's, or risk's collared cap for unpriced MARKET. */
    Order accepted(long effectivePrice) {
        return new Order(
                id,
                account,
                symbol,
                side,
                orderType,
                effectivePrice,
                quantity,
                filledQty,
                OrderStatus.ACCEPTED,
                null,
                version);
    }

    Order rejected(String reason) {
        return new Order(
                id,
                account,
                symbol,
                side,
                orderType,
                price,
                quantity,
                filledQty,
                OrderStatus.REJECTED,
                reason,
                version);
    }

    Order withFill(long addQty) {
        long total = filledQty + addQty;
        OrderStatus next = total >= quantity ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        return new Order(id, account, symbol, side, orderType, price, quantity, total, next, null, version);
    }

    Order cancelled() {
        return new Order(
                id, account, symbol, side, orderType, price, quantity, filledQty, OrderStatus.CANCELLED, null, version);
    }

    /** Book a fill without changing status — for a fill that arrives after the order is CANCELLED. */
    Order withFillKeepingStatus(long addQty) {
        return new Order(
                id,
                account,
                symbol,
                side,
                orderType,
                price,
                quantity,
                filledQty + addQty,
                status,
                rejectReason,
                version);
    }
}
