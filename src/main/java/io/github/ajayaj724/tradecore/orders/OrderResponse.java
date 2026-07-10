package io.github.ajayaj724.tradecore.orders;

import io.github.ajayaj724.tradecore.shared.OrderType;
import io.github.ajayaj724.tradecore.shared.Side;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Prices and quantities are minor units (paise / shares). Display formatting is a UI concern. */
record OrderResponse(
        long id,
        String account,
        String symbol,
        Side side,
        OrderType type,
        long price,
        long quantity,
        long filledQty,
        String status,
        @Nullable String rejectReason) {

    static OrderResponse from(Order o) {
        return new OrderResponse(
                Objects.requireNonNull(o.id()),
                o.account(),
                o.symbol(),
                o.side(),
                o.orderType(),
                o.price(),
                o.quantity(),
                o.filledQty(),
                o.status().name(),
                o.rejectReason());
    }
}
