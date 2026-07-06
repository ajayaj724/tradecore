package io.github.ajayaj724.tradecore.events;

import java.util.concurrent.CountDownLatch;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

public class EventFixtures {

    public record PingEvent(String value) {}

    @Component
    public static class PingPublisher {
        private final ApplicationEventPublisher events;

        PingPublisher(ApplicationEventPublisher events) {
            this.events = events;
        }

        @Transactional
        public void ping(String value) {
            events.publishEvent(new PingEvent(value));
        }
    }

    @Component
    public static class PingListener {
        public static final CountDownLatch RECEIVED = new CountDownLatch(1);

        @ApplicationModuleListener
        void on(PingEvent event) {
            RECEIVED.countDown();
        }
    }
}
