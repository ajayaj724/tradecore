package io.github.ajayaj724.tradecore.execution;

import io.github.ajayaj724.tradecore.shared.OrderAccepted;
import io.github.ajayaj724.tradecore.shared.OrderCancelRequested;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.util.List;

/** Venue boundary (hexagonal port). Phase 1B ships the embedded engine adapter only. */
public interface ExecutionVenue {
    List<TradeExecuted> submit(OrderAccepted order);

    void cancel(OrderCancelRequested request);
}
