package io.github.ajayaj724.tradecore.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.PriceUpdated;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/**
 * The scheduled feed polls every few seconds; without publish-on-change it would emit a {@link
 * PriceUpdated} (and grow the event-publication + downstream processed-event tables) on every poll
 * even when the price is unchanged. These tests pin that {@code applyExternalPrice} publishes only
 * when the last price actually moves.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class MarketDataPublishOnChangeIT {

    private final MarketDataService md;
    private final PriceUpdateCounter counter;

    @Autowired
    MarketDataPublishOnChangeIT(MarketDataService md, PriceUpdateCounter counter) {
        this.md = md;
        this.counter = counter;
    }

    @Test
    void republishingSamePriceEmitsNoDuplicatePriceUpdated() {
        int before = counter.countFor("PUBSAME");

        md.applyExternalPrice("PUBSAME", 55500L, Instant.EPOCH); // first sighting: a change
        md.applyExternalPrice("PUBSAME", 55500L, Instant.EPOCH); // identical: must be suppressed

        assertThat(counter.countFor("PUBSAME")).isEqualTo(before + 1);
    }

    @Test
    void changedPriceEmitsPriceUpdated() {
        int before = counter.countFor("PUBDIFF");

        md.applyExternalPrice("PUBDIFF", 66600L, Instant.EPOCH);
        md.applyExternalPrice("PUBDIFF", 66601L, Instant.EPOCH);

        assertThat(counter.countFor("PUBDIFF")).isEqualTo(before + 2);
    }

    @TestConfiguration
    static class Config {
        @Bean
        PriceUpdateCounter priceUpdateCounter() {
            return new PriceUpdateCounter();
        }
    }

    /** Counts {@link PriceUpdated} events synchronously, per symbol, as they are published. */
    static class PriceUpdateCounter {
        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        @EventListener
        void on(PriceUpdated event) {
            counts.computeIfAbsent(event.symbol(), s -> new AtomicInteger()).incrementAndGet();
        }

        int countFor(String symbol) {
            AtomicInteger c = counts.get(symbol);
            return c == null ? 0 : c.get();
        }
    }
}
