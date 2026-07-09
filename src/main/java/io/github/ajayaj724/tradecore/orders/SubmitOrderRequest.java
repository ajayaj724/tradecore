package io.github.ajayaj724.tradecore.orders;

import io.github.ajayaj724.tradecore.shared.OrderType;
import io.github.ajayaj724.tradecore.shared.Side;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.jspecify.annotations.Nullable;

record SubmitOrderRequest(
        @NotBlank String symbol,
        @NotNull Side side,
        @Positive long price, // for MARKET this is the protective cap
        @Positive long quantity,
        @Nullable OrderType type) {

    /** The requested type, defaulting to LIMIT when the client omits it (preserves prior behavior). */
    OrderType typeOrDefault() {
        return type == null ? OrderType.LIMIT : type;
    }
}
