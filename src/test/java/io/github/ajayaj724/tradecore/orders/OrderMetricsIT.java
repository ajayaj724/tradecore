package io.github.ajayaj724.tradecore.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.Side;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class OrderMetricsIT {

    private final OrderService orders;
    private final MeterRegistry registry;

    @Autowired
    OrderMetricsIT(OrderService orders, MeterRegistry registry) {
        this.orders = orders;
        this.registry = registry;
    }

    @Test
    void submitIncrementsSubmittedCounter() {
        double before = registry.counter("tradecore.orders.submitted").count();
        orders.submit(
                "trader1",
                "trader1",
                new SubmitOrderCommand("k-" + java.util.UUID.randomUUID(), "ACME", Side.BUY, 100L, 1L));
        assertThat(registry.counter("tradecore.orders.submitted").count()).isEqualTo(before + 1);
    }

    @Test
    void riskRejectionIncrementsRejectionCounterTaggedByReason() {
        // BUY 2_000_000 shares * 100 paise = 200_000_000 paise > seeded 100_000_000 cash -> insufficient cash
        orders.submit(
                "trader1",
                "trader1",
                new SubmitOrderCommand("k-" + java.util.UUID.randomUUID(), "ACME", Side.BUY, 100L, 2_000_000L));
        assertThat(registry.get("tradecore.risk.rejections")
                        .tag("reason", "insufficient cash")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1d);
    }

    @Test
    void fillRecordsFillLatencyTimer() {
        long before = registry.get("tradecore.order.fill.latency").timer().count();
        // A matching SELL + BUY on ACME cross at 100 -> engine produces a fill -> both orders reach
        // FILLED -> recordFillLatency looks up each order's SUBMITTED audit row and records the timer.
        orders.submit(
                "trader2", "trader2", new SubmitOrderCommand("k-" + UUID.randomUUID(), "ACME", Side.SELL, 100L, 1L));
        orders.submit(
                "trader1", "trader1", new SubmitOrderCommand("k-" + UUID.randomUUID(), "ACME", Side.BUY, 100L, 1L));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                        registry.get("tradecore.order.fill.latency").timer().count())
                .isGreaterThan(before));
    }
}
