package io.github.ajayaj724.tradecore.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.PriceUpdated;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfig.class)
class ReferencePriceProjectionIT {

    private static final Instant T0 = Instant.parse("2026-07-10T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-10T10:00:05Z");

    private final RiskService risk;
    private final JdbcClient jdbc;

    @Autowired
    ReferencePriceProjectionIT(RiskService risk, JdbcClient jdbc) {
        this.risk = risk;
        this.jdbc = jdbc;
    }

    private @Nullable Long referencePrice(String symbol) {
        return jdbc.sql("select price from risk.reference_price where symbol = :s")
                .param("s", symbol)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    @Test
    void appliesPriceUpdatedToTheProjection() {
        risk.applyPriceUpdated(new PriceUpdated(UUID.randomUUID(), "REF-A", 12345, T0));

        assertThat(referencePrice("REF-A")).isEqualTo(12345);
    }

    @Test
    void duplicateDeliveryHasASingleEffect() {
        PriceUpdated event = new PriceUpdated(UUID.randomUUID(), "REF-B", 11111, T0);

        risk.applyPriceUpdated(event);
        risk.applyPriceUpdated(event);

        Long rows = jdbc.sql("select count(*) from risk.reference_price where symbol = 'REF-B'")
                .query(Long.class)
                .single();
        assertThat(rows).isEqualTo(1);
        assertThat(referencePrice("REF-B")).isEqualTo(11111);
    }

    @Test
    void staleRedeliveryDoesNotRegressANewerPrice() {
        PriceUpdated old = new PriceUpdated(UUID.randomUUID(), "REF-C", 10000, T0);
        PriceUpdated fresh = new PriceUpdated(UUID.randomUUID(), "REF-C", 20000, T1);

        risk.applyPriceUpdated(old);
        risk.applyPriceUpdated(fresh);
        risk.applyPriceUpdated(old); // at-least-once redelivery of the older event

        assertThat(referencePrice("REF-C")).isEqualTo(20000);
    }
}
