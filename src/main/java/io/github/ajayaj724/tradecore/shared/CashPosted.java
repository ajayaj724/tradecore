package io.github.ajayaj724.tradecore.shared;

import java.time.Instant;
import java.util.UUID;

public record CashPosted(UUID eventId, String account, long amount, Instant occurredAt) {}
