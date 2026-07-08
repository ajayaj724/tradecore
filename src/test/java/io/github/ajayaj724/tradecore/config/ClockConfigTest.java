package io.github.ajayaj724.tradecore.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

    @Test
    void providesUtcSystemClock() {
        Clock clock = new ClockConfig().clock();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
