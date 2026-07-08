package io.github.ajayaj724.tradecore.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfig.class)
class EventRegistryLagMetricsIT {

    private final MeterRegistry registry;
    private final JdbcClient jdbc;

    @Autowired
    EventRegistryLagMetricsIT(MeterRegistry registry, JdbcClient jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @Test
    void gaugeReflectsIncompletePublicationCount() {
        double gauge = registry.get("tradecore.events.registry.lag").gauge().value();
        Long direct = jdbc.sql("select count(*) from event_publication where completion_date is null")
                .query(Long.class)
                .single();
        assertThat(gauge).isEqualTo(direct.doubleValue());
    }
}
