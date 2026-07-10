package io.github.ajayaj724.tradecore.portfolio;

import org.jspecify.annotations.Nullable;

/**
 * All money in paise, all integer (ADR-0008): unrealized = mark×qty − totalCost, or 0 when the
 * symbol has never been marked. Average cost is a display concern (totalCost / quantity at the UI).
 */
record PositionResponse(
        String symbol,
        long quantity,
        long totalCost,
        @Nullable Long markPrice,
        long realizedPnl,
        long unrealizedPnl) {}
