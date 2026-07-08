package io.github.ajayaj724.tradecore.marketdata;

import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class MarketDataListener {

    private final MarketDataService md;

    MarketDataListener(MarketDataService md) {
        this.md = md;
    }

    @ApplicationModuleListener
    void on(TradeExecuted trade) {
        md.onTrade(trade);
    }
}
