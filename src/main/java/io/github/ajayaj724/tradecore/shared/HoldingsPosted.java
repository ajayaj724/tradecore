package io.github.ajayaj724.tradecore.shared;

import java.time.Instant;
import java.util.UUID;

public record HoldingsPosted(UUID eventId, String account, String symbol, long qty, Instant occurredAt) {}
