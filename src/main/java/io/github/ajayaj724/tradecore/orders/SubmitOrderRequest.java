package io.github.ajayaj724.tradecore.orders;

import io.github.ajayaj724.tradecore.shared.Side;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

record SubmitOrderRequest(
        @NotBlank String symbol,
        @NotNull Side side,
        @Positive long price,
        @Positive long quantity) {}
