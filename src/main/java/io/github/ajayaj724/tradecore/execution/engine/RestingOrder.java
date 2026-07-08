package io.github.ajayaj724.tradecore.execution.engine;

final class RestingOrder {
    final long orderId;
    final long price;
    long remaining;

    RestingOrder(long orderId, long price, long remaining) {
        this.orderId = orderId;
        this.price = price;
        this.remaining = remaining;
    }
}
