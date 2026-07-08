package io.github.ajayaj724.tradecore.shared;

import java.time.Instant;
import java.util.UUID;

public record PriceUpdated(UUID eventId, String symbol, long price, Instant occurredAt) {}
