package io.github.ajayaj724.tradecore.marketdata;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Publishes seconds since the Upstox feed last succeeded — a stuck/degraded feed rises even when
 * the breaker is quiet.
 */
@Component
class UpstoxFeedMetrics {

    private final UpstoxPriceFeed feed;
    private final Clock clock;

    UpstoxFeedMetrics(UpstoxPriceFeed feed, Clock clock, MeterRegistry registry) {
        this.feed = feed;
        this.clock = clock;
        Gauge.builder("tradecore.marketdata.feed.staleness.seconds", this, UpstoxFeedMetrics::stalenessSeconds)
                .description("seconds since the Upstox market-data feed last succeeded")
                .register(registry);
    }

    double stalenessSeconds() {
        return Duration.between(feed.lastSuccess(), clock.instant()).toSeconds();
    }
}
