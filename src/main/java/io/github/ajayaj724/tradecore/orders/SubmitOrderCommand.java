package io.github.ajayaj724.tradecore.orders;

import io.github.ajayaj724.tradecore.shared.OrderType;
import io.github.ajayaj724.tradecore.shared.Side;

record SubmitOrderCommand(String idempotencyKey, String symbol, Side side, long price, long quantity, OrderType type) {}
