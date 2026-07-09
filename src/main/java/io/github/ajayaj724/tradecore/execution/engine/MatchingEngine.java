package io.github.ajayaj724.tradecore.execution.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Single embedded limit-order-book matching engine. Framework-free and deterministic.
 * Synchronous in Phase 1B; the {@code synchronized} guard is the seam a Phase 3
 * single-writer-per-symbol executor replaces.
 */
public final class MatchingEngine {

    private final Map<String, OrderBook> books = new HashMap<>();

    public synchronized List<Fill> submit(String symbol, long orderId, Side side, long limitPrice, long quantity) {
        return submit(symbol, orderId, side, limitPrice, quantity, true);
    }

    /**
     * Immediate-or-cancel: match against available liquidity and drop any unfilled remainder instead
     * of resting it. Used for market orders (a marketable-limit capped at {@code limitPrice}). Atomic
     * under the engine lock, so the remainder is never briefly visible to a concurrent order.
     */
    public synchronized List<Fill> submitIoc(String symbol, long orderId, Side side, long limitPrice, long quantity) {
        return submit(symbol, orderId, side, limitPrice, quantity, false);
    }

    private List<Fill> submit(String symbol, long orderId, Side side, long limitPrice, long quantity, boolean rest) {
        if (limitPrice <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("price and quantity must be positive");
        }
        return books.computeIfAbsent(symbol, s -> new OrderBook()).submit(orderId, side, limitPrice, quantity, rest);
    }

    public synchronized long cancel(String symbol, long orderId) {
        OrderBook book = books.get(symbol);
        return book == null ? 0 : book.cancel(orderId);
    }

    public synchronized OptionalLong bestBid(String symbol) {
        OrderBook book = books.get(symbol);
        return book == null ? OptionalLong.empty() : book.bestBid();
    }

    public synchronized OptionalLong bestAsk(String symbol) {
        OrderBook book = books.get(symbol);
        return book == null ? OptionalLong.empty() : book.bestAsk();
    }

    public synchronized long openQuantity(String symbol, long orderId) {
        OrderBook book = books.get(symbol);
        return book == null ? 0 : book.openQuantity(orderId);
    }
}
