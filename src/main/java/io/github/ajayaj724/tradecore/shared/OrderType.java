package io.github.ajayaj724.tradecore.shared;

/**
 * LIMIT rests any unfilled remainder on the book. MARKET is immediate-or-cancel against a protective
 * cap price: it matches available liquidity and cancels the remainder rather than resting it.
 */
public enum OrderType {
    LIMIT,
    MARKET
}
