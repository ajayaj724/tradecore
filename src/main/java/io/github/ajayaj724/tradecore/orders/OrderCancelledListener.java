package io.github.ajayaj724.tradecore.orders;

import io.github.ajayaj724.tradecore.shared.OrderCancelled;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class OrderCancelledListener {

    private final OrderService service;

    OrderCancelledListener(OrderService service) {
        this.service = service;
    }

    @ApplicationModuleListener
    void on(OrderCancelled event) {
        service.applyCancel(event);
    }
}
