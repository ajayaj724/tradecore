package io.github.ajayaj724.tradecore.orders;

class CancelRequestNotFoundException extends RuntimeException {

    CancelRequestNotFoundException(long id) {
        super("cancel request %d not found".formatted(id));
    }
}
