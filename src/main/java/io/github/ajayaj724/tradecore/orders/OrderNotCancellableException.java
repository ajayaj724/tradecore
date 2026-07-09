package io.github.ajayaj724.tradecore.orders;

class OrderNotCancellableException extends RuntimeException {
    OrderNotCancellableException(long id, OrderStatus status) {
        super("Order " + id + " cannot be cancelled in status " + status);
    }
}
