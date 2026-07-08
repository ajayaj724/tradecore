package io.github.ajayaj724.tradecore.risk;

import io.github.ajayaj724.tradecore.shared.CashPosted;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class RiskProjectionListener {

    private final RiskService risk;

    RiskProjectionListener(RiskService risk) {
        this.risk = risk;
    }

    @ApplicationModuleListener
    void onCashPosted(CashPosted event) {
        risk.applyCashPosted(event);
    }

    @ApplicationModuleListener
    void onTradeExecuted(TradeExecuted trade) {
        risk.releaseHold(trade);
    }
}
