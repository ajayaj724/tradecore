package io.github.ajayaj724.tradecore.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class FeedStalenessIT {

    private final MeterRegistry registry;

    @Autowired
    FeedStalenessIT(MeterRegistry registry) {
        this.registry = registry;
    }

    @Test
    void publishesNonNegativeStalenessGauge() {
        double staleness = registry.get("tradecore.marketdata.feed.staleness.seconds")
                .gauge()
                .value();
        // fresh context: lastSuccess() was set at construction time, so staleness is small.
        assertThat(staleness).isGreaterThanOrEqualTo(0d).isLessThan(60d);
    }

    @Test
    void publishesUpstoxCircuitBreakerStateGauge() {
        assertThat(registry.find("resilience4j.circuitbreaker.state").gauges()).isNotEmpty();
    }
}
