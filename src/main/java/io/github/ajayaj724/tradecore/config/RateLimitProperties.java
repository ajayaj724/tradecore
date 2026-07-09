package io.github.ajayaj724.tradecore.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-user token-bucket policy for the API edge: {@code capacity} tokens refilled over a period. */
@ConfigurationProperties("tradecore.ratelimit")
record RateLimitProperties(int capacity, Duration refillPeriod) {

    RateLimitProperties {
        if (capacity <= 0) {
            capacity = 100;
        }
        if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
            refillPeriod = Duration.ofMinutes(1);
        }
    }
}
