package io.github.ajayaj724.tradecore.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Publishes the event-publication backlog — Spring Modulith rows whose {@code completion_date} is
 * still null — as a gauge. A rising value means consumers are lagging or failing.
 */
@Component
class EventRegistryLagMetrics {

    private final JdbcClient jdbc;

    EventRegistryLagMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("tradecore.events.registry.lag", this, EventRegistryLagMetrics::incompletePublications)
                .description("event_publication rows not yet completed (consumer backlog)")
                .register(registry);
    }

    double incompletePublications() {
        Long count = jdbc.sql("select count(*) from event_publication where completion_date is null")
                .query(Long.class)
                .single();
        return count == null ? 0d : count.doubleValue();
    }
}
