package io.github.ajayaj724.tradecore.execution.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * One symbol's limit order book. Two price-ordered sides, each a FIFO queue of resting orders
 * per price level: bids highest-price-first, asks lowest-price-first.
 */
final class OrderBook {

    private final NavigableMap<Long, Deque<RestingOrder>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, Deque<RestingOrder>> asks = new TreeMap<>();

    /**
     * Match an incoming LIMIT order against the opposite side by price-time priority, printing each
     * trade at the resting (maker) order's price, then rest any unfilled remainder on its own side.
     */
    List<Fill> submit(long orderId, Side side, long limitPrice, long quantity) {
        List<Fill> fills = new ArrayList<>();
        long remaining = quantity;
        NavigableMap<Long, Deque<RestingOrder>> opposite = side == Side.BUY ? asks : bids;
        while (remaining > 0 && crossable(side, limitPrice, opposite)) {
            Deque<RestingOrder> level =
                    Objects.requireNonNull(opposite.firstEntry()).getValue();
            RestingOrder resting = Objects.requireNonNull(level.peekFirst());
            long traded = Math.min(remaining, resting.remaining);
            fills.add(fill(side, orderId, resting, traded));
            remaining -= traded;
            resting.remaining -= traded;
            if (resting.remaining == 0) {
                level.pollFirst();
                if (level.isEmpty()) {
                    opposite.pollFirstEntry();
                }
            }
        }
        if (remaining > 0) {
            own(side)
                    .computeIfAbsent(limitPrice, p -> new ArrayDeque<>())
                    .addLast(new RestingOrder(orderId, limitPrice, remaining));
        }
        return fills;
    }

    private static boolean crossable(Side side, long limitPrice, NavigableMap<Long, Deque<RestingOrder>> opposite) {
        if (opposite.isEmpty()) {
            return false;
        }
        long best = opposite.firstKey();
        return side == Side.BUY ? best <= limitPrice : best >= limitPrice;
    }

    private static Fill fill(Side side, long incomingId, RestingOrder resting, long qty) {
        return side == Side.BUY
                ? new Fill(incomingId, resting.orderId, resting.price, qty)
                : new Fill(resting.orderId, incomingId, resting.price, qty);
    }

    private NavigableMap<Long, Deque<RestingOrder>> own(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    OptionalLong bestBid() {
        return bids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(bids.firstKey());
    }

    OptionalLong bestAsk() {
        return asks.isEmpty() ? OptionalLong.empty() : OptionalLong.of(asks.firstKey());
    }

    long openQuantity(long orderId) {
        return sumRemaining(bids, orderId) + sumRemaining(asks, orderId);
    }

    private static long sumRemaining(NavigableMap<Long, Deque<RestingOrder>> book, long orderId) {
        long sum = 0;
        for (Deque<RestingOrder> level : book.values()) {
            for (RestingOrder r : level) {
                if (r.orderId == orderId) {
                    sum += r.remaining;
                }
            }
        }
        return sum;
    }
}
