package io.github.ajayaj724.tradecore.orders;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.shared.Side;
import org.junit.jupiter.api.Test;

class OrderTest {

    @Test
    void cancelledSetsStatusToCancelledAndKeepsFillProgress() {
        Order partiallyFilled = Order.newOrder("trader1", "ACME", Side.BUY, 10000L, 5L)
                .accepted(10000L)
                .withFill(2L);

        Order cancelled = partiallyFilled.cancelled();

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.filledQty()).isEqualTo(2L); // fills already booked are preserved
        assertThat(cancelled.quantity()).isEqualTo(5L);
        assertThat(cancelled.rejectReason()).isNull();
    }
}
