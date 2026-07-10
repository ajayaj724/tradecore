package io.github.ajayaj724.tradecore.orders;

import io.github.ajayaj724.tradecore.shared.OrderType;
import io.github.ajayaj724.tradecore.shared.Side;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.jspecify.annotations.Nullable;

record SubmitOrderRequest(
        @NotBlank String symbol,
        @NotNull Side side,
        // LIMIT: the limit price. MARKET: an optional protective cap — omitted means the
        // risk module derives one from the reference price plus a collar.
        @Positive @Nullable Long price,
        @Positive long quantity,
        @Nullable OrderType type) {

    /** The requested type, defaulting to LIMIT when the client omits it (preserves prior behavior). */
    OrderType typeOrDefault() {
        return type == null ? OrderType.LIMIT : type;
    }

    @AssertTrue(message = "price is required for LIMIT orders")
    boolean isPricedWhenLimit() {
        return typeOrDefault() == OrderType.MARKET || price != null;
    }
}
