package io.github.ajayaj724.tradecore.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(
        classes = {
            io.github.ajayaj724.tradecore.TradecoreApplication.class,
            EventFixtures.PingPublisher.class,
            EventFixtures.PingListener.class
        })
@Import(TestcontainersConfig.class)
class EventRegistryIT {

    private final EventFixtures.PingPublisher publisher;
    private final JdbcClient jdbc;

    @Autowired
    EventRegistryIT(EventFixtures.PingPublisher publisher, JdbcClient jdbc) {
        this.publisher = publisher;
        this.jdbc = jdbc;
    }

    @Test
    void persistsAndCompletesEventPublications() throws Exception {
        publisher.ping("hello");

        assertThat(EventFixtures.PingListener.RECEIVED.await(10, TimeUnit.SECONDS))
                .isTrue();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Long completed = jdbc.sql("select count(*) from event_publication where completion_date is not null")
                    .query(Long.class)
                    .single();
            assertThat(completed).isGreaterThanOrEqualTo(1L);
        });
    }
}
