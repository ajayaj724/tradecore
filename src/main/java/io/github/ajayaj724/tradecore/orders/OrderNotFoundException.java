package io.github.ajayaj724.tradecore.orders;

class OrderNotFoundException extends RuntimeException {
    OrderNotFoundException(long id) {
        super("Order not found: " + id);
    }
}
