package io.github.ajayaj724.tradecore.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reconciliation must cover every tradable symbol, not just ACME (multi-symbol gap). */
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

    private int driftedPairsFor(String account) {
        return service.report().accounts().stream()
                .filter(a -> a.account().equals(account))
                .findFirst()
                .orElseThrow()
                .driftedPairs();
    }

    @Test
    void driftInANonAcmeSymbolIsDetected() {
        // Perturb the seeded INFY holding so risk disagrees with portfolio, then restore.
        jdbc.sql("update risk.settled_holdings set qty = qty + 7 where account = 'trader1' and symbol = 'INFY'")
                .update();
        try {
            // Only reachable if INFY is in the reconciled universe.
            assertThat(driftedPairsFor("trader1")).isGreaterThanOrEqualTo(1);
        } finally {
            jdbc.sql("update risk.settled_holdings set qty = qty - 7 where account = 'trader1' and symbol = 'INFY'")
                    .update();
        }
    }
}
