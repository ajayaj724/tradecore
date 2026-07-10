package io.github.ajayaj724.tradecore.orders;

import io.github.ajayaj724.tradecore.shared.OrderType;
import io.github.ajayaj724.tradecore.shared.Side;
import org.jspecify.annotations.Nullable;

record SubmitOrderCommand(
        String idempotencyKey,
        String symbol,
        Side side,
        @Nullable Long price, // null = unpriced MARKET; risk derives the effective cap
        long quantity,
        OrderType type) {}
