package io.github.ajayaj724.tradecore.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.PriceUpdated;
import io.github.ajayaj724.tradecore.shared.Side;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfig.class)
class UnpricedRiskCheckIT {

    private final RiskService risk;
    private final JdbcClient jdbc;
    private final Instant now;

    @Autowired
    UnpricedRiskCheckIT(RiskService risk, JdbcClient jdbc, Clock clock) {
        this.risk = risk;
        this.jdbc = jdbc;
        this.now = clock.instant();
    }

    @Test
    void unpricedBuyReservesAtTheCollaredReferencePrice() {
        risk.applyPriceUpdated(new PriceUpdated(UUID.randomUUID(), "UNP-A", 10000, now));
        jdbc.sql("insert into risk.settled_cash (account, amount) values ('unp-buyer', 1000000)")
                .update();

        RiskDecision decision = risk.check(880001L, "unp-buyer", Side.BUY, "UNP-A", null, 5);

        // 5% collar over the 10000 reference: the hold and the engine cap are both 10500.
        assertThat(decision).isEqualTo(new RiskDecision.Approved(10500L));
        Long holdPrice = jdbc.sql("select unit_price from risk.cash_hold where order_id = 880001")
                .query(Long.class)
                .single();
        assertThat(holdPrice).isEqualTo(10500);
    }

    @Test
    void unpricedSellIsFlooredAtTheCollaredReferencePrice() {
        risk.applyPriceUpdated(new PriceUpdated(UUID.randomUUID(), "UNP-B", 10000, now));
        jdbc.sql("insert into risk.settled_holdings (account, symbol, qty) values ('unp-seller', 'UNP-B', 10)")
                .update();

        RiskDecision decision = risk.check(880002L, "unp-seller", Side.SELL, "UNP-B", null, 5);

        assertThat(decision).isEqualTo(new RiskDecision.Approved(9500L));
    }

    @Test
    void unpricedOrderIsRejectedWhenTheReferenceIsStale() {
        // Two hours old — beyond the default PT1H window.
        risk.applyPriceUpdated(new PriceUpdated(UUID.randomUUID(), "UNP-STALE", 10000, now.minus(Duration.ofHours(2))));
        jdbc.sql("insert into risk.settled_cash (account, amount) values ('unp-stale', 1000000)")
                .update();

        RiskDecision decision = risk.check(880005L, "unp-stale", Side.BUY, "UNP-STALE", null, 5);

        assertThat(decision).isEqualTo(new RiskDecision.Rejected("stale reference price"));
    }

    @Test
    void unpricedOrderIsRejectedWithoutAReferencePrice() {
        jdbc.sql("insert into risk.settled_cash (account, amount) values ('unp-noref', 1000000)")
                .update();

        RiskDecision decision = risk.check(880003L, "unp-noref", Side.BUY, "UNP-NONE", null, 5);

        assertThat(decision).isEqualTo(new RiskDecision.Rejected("no reference price"));
    }

    @Test
    void pricedCheckStillUsesTheClientsPriceAsTheEffectivePrice() {
        jdbc.sql("insert into risk.settled_cash (account, amount) values ('unp-priced', 1000000)")
                .update();

        RiskDecision decision = risk.check(880004L, "unp-priced", Side.BUY, "UNP-A", 9000L, 5);

        assertThat(decision).isEqualTo(new RiskDecision.Approved(9000L));
    }
}
