package io.github.ajayaj724.tradecore.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reconciliation must cover every tradable symbol, not just the seeded ACME (multi-symbol gap). */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ReconciliationCoverageIT {

    private final ReconciliationService service;
    private final JdbcClient jdbc;

    @Autowired
    ReconciliationCoverageIT(ReconciliationService service, JdbcClient jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("delete from risk.settled_holdings where account = 'trader1' and symbol = 'INFY'")
                .update();
    }

    @Test
    void driftInATradableNonSeedSymbolIsDetected() {
        // trader1 has no INFY position, but risk shows a phantom INFY holding — a real drift.
        jdbc.sql("delete from risk.settled_holdings where account = 'trader1' and symbol = 'INFY'")
                .update();
        jdbc.sql("insert into risk.settled_holdings (account, symbol, qty) values ('trader1', 'INFY', 5)")
                .update();

        ReconciliationReport.AccountHealth trader1 = service.report().accounts().stream()
                .filter(a -> a.account().equals("trader1"))
                .findFirst()
                .orElseThrow();

        // Only reachable if INFY is in the reconciled universe.
        assertThat(trader1.driftedPairs()).isGreaterThanOrEqualTo(1);
    }
}
