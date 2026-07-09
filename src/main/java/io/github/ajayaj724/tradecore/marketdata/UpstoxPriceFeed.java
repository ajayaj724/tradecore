package io.github.ajayaj724.tradecore.marketdata;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls Upstox LTP for the configured symbols and applies successful prices to the read-model. */
@Component
class UpstoxPriceFeed {

    private final UpstoxClient client;
    private final MarketDataService marketData;
    private final UpstoxProperties props;
    private final Clock clock;
    private final AtomicReference<Instant> lastSuccess;

    UpstoxPriceFeed(UpstoxClient client, MarketDataService marketData, UpstoxProperties props, Clock clock) {
        this.client = client;
        this.marketData = marketData;
        this.props = props;
        this.clock = clock;
        this.lastSuccess = new AtomicReference<>(clock.instant());
    }

    @Scheduled(
            initialDelayString = "${tradecore.upstox.poll.initial-delay-ms:5000}",
            fixedDelayString = "${tradecore.upstox.poll.fixed-delay-ms:5000}")
    public void pollOnce() {
        Instant observedAt = clock.instant();
        boolean anySuccess = false;
        for (String symbol : props.instrumentKeys().keySet()) {
            long price = client.ltp(symbol); // resilient; UNAVAILABLE on failure/open circuit
            if (price != UpstoxClient.UNAVAILABLE) {
                marketData.applyExternalPrice(symbol, price, observedAt);
                anySuccess = true;
            }
        }
        if (anySuccess) {
            lastSuccess.set(observedAt);
        }
    }

    Instant lastSuccess() {
        // never null: initialized in the constructor and only ever re-set to a non-null Instant.
        return Objects.requireNonNull(lastSuccess.get());
    }
}
