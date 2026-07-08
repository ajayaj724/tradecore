package io.github.ajayaj724.tradecore.shared;

import java.time.Instant;
import java.util.UUID;

public record TradeExecuted(
        UUID eventId,
        long buyOrderId,
        long sellOrderId,
        String symbol,
        long price,
        long quantity,
        Instant occurredAt) {}
