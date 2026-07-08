package io.github.ajayaj724.tradecore.shared;

import java.time.Instant;
import java.util.UUID;

public record OrderRejected(
        UUID eventId,
        long orderId,
        String account,
        String symbol,
        Side side,
        long price,
        long quantity,
        String reason,
        Instant occurredAt) {}
