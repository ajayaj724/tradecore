package io.github.ajayaj724.tradecore.shared;

import java.time.Instant;
import java.util.UUID;

public record OrderAccepted(
        UUID eventId,
        long orderId,
        String account,
        String symbol,
        Side side,
        OrderType type,
        long price,
        long quantity,
        Instant occurredAt) {}
