package io.github.ajayaj724.tradecore.orders;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.shared.OrderType;
import io.github.ajayaj724.tradecore.shared.Side;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class SubmitOrderRequestTest {

    private static final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void omittedPriceIsValidForMarketOrders() {
        SubmitOrderRequest request = new SubmitOrderRequest("ACME", Side.BUY, null, 5L, OrderType.MARKET);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void omittedPriceIsRejectedForLimitOrders() {
        SubmitOrderRequest request = new SubmitOrderRequest("ACME", Side.BUY, null, 5L, null);

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void omittedTypeDefaultsToLimit() {
        // Existing clients send no "type" field; it must resolve to LIMIT to preserve behavior.
        SubmitOrderRequest request = new SubmitOrderRequest("ACME", Side.BUY, 10000L, 5L, null);

        assertThat(request.typeOrDefault()).isEqualTo(OrderType.LIMIT);
    }

    @Test
    void explicitMarketTypeIsKept() {
        SubmitOrderRequest request = new SubmitOrderRequest("ACME", Side.BUY, 10000L, 5L, OrderType.MARKET);

        assertThat(request.typeOrDefault()).isEqualTo(OrderType.MARKET);
    }
}
