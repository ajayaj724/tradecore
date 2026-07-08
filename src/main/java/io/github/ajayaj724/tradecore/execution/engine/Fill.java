package io.github.ajayaj724.tradecore.execution.engine;

public record Fill(long buyOrderId, long sellOrderId, long price, long quantity) {
    public Fill {
        if (price <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("price and quantity must be positive");
        }
    }
}
