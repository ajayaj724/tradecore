package io.github.ajayaj724.tradecore.shared;

import java.time.Instant;
import java.util.UUID;

/**
 * The matching engine has removed an order's resting remainder from the book. {@code cancelledQty}
 * is the quantity that was still open (0 if the order was already fully filled or never rested).
 * Consumed by {@code orders} (marks the order CANCELLED) and {@code risk} (releases the unfilled hold).
 */
public record OrderCancelled(
        UUID eventId, long orderId, String account, String symbol, Side side, long cancelledQty, Instant occurredAt) {}
