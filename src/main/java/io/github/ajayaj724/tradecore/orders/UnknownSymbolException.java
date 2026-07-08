package io.github.ajayaj724.tradecore.orders;

class UnknownSymbolException extends RuntimeException {
    UnknownSymbolException(String symbol) {
        super("Unknown symbol: " + symbol);
    }
}
