package io.github.ajayaj724.tradecore.ledger;

import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class LedgerListener {

    private final LedgerService ledger;

    LedgerListener(LedgerService ledger) {
        this.ledger = ledger;
    }

    @ApplicationModuleListener
    void on(TradeExecuted trade) {
        ledger.post(trade);
    }
}
