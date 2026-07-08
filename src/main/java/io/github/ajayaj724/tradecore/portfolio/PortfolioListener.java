package io.github.ajayaj724.tradecore.portfolio;

import io.github.ajayaj724.tradecore.shared.PriceUpdated;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class PortfolioListener {

    private final PortfolioService portfolio;

    PortfolioListener(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @ApplicationModuleListener
    void onTrade(TradeExecuted trade) {
        portfolio.onTrade(trade);
    }

    @ApplicationModuleListener
    void onPrice(PriceUpdated price) {
        portfolio.onPrice(price);
    }
}
