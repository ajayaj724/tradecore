package io.github.ajayaj724.tradecore.shared;

import java.time.Instant;
import java.util.UUID;

/**
 * A trader has asked to cancel a working order. Published by {@code orders} (which may not call the
 * matching engine directly) and consumed by {@code execution}, which removes any resting remainder
 * from the book and answers with {@link OrderCancelled}.
 */
public record OrderCancelRequested(
        UUID eventId, long orderId, String account, String symbol, Side side, Instant occurredAt) {}
