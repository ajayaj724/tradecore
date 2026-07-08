package io.github.ajayaj724.tradecore.orders;

import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class OrderFillListener {

    private final OrderService service;

    OrderFillListener(OrderService service) {
        this.service = service;
    }

    @ApplicationModuleListener
    void on(TradeExecuted trade) {
        service.applyTrade(trade);
    }
}
