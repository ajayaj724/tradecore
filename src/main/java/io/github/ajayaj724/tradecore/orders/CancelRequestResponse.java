package io.github.ajayaj724.tradecore.orders;

record CancelRequestResponse(long id, long orderId, String account, String symbol, String requestedBy, String status) {}
