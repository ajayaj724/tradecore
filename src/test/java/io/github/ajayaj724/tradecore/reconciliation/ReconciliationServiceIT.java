package io.github.ajayaj724.tradecore.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfig.class)
@TestPropertySource(
        properties = {
            "tradecore.reconciliation.accounts=recon-acct",
            "tradecore.reconciliation.symbols=RCT",
            "tradecore.reconciliation.initial-delay-ms=3600000"
        })
class ReconciliationServiceIT {

    private final ReconciliationService reconciliation;
    private final MeterRegistry registry;
    private final JdbcClient jdbc;

    @Autowired
    ReconciliationServiceIT(ReconciliationService reconciliation, MeterRegistry registry, JdbcClient jdbc) {
        this.reconciliation = reconciliation;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    private double driftPairs() {
        return registry.get("tradecore.reconciliation.drift.pairs").gauge().value();
    }

    private double equity(String account) {
        return registry.get("tradecore.account.equity")
                .tag("account", account)
                .gauge()
                .value();
    }

    @Test
    void emptyUniverseReportsZeroDriftAndZeroEquity() {
        // recon-acct/RCT have no ledger/risk/portfolio/price rows -> every read is 0, nothing drifts
        reconciliation.reconcile();
        assertThat(driftPairs()).isZero();
        assertThat(equity("recon-acct")).isZero();
    }

    @Test
    @Transactional
    void consistentStateReportsZeroDriftAndComputesEquity() {
        // ledger cash == risk settled cash (500); portfolio qty == risk settled holdings (3); price 100
        jdbc.sql("insert into ledger.posting (txn_id, account, amount, kind) values (:t, 'recon-acct', 500, 'OPENING')")
                .param("t", UUID.randomUUID())
                .update();
        jdbc.sql("insert into risk.settled_cash (account, amount) values ('recon-acct', 500)")
                .update();
        jdbc.sql("insert into portfolio.position (account, symbol, total_qty) values ('recon-acct', 'RCT', 3)")
                .update();
        jdbc.sql("insert into risk.settled_holdings (account, symbol, qty) values ('recon-acct', 'RCT', 3)")
                .update();
        jdbc.sql("insert into marketdata.last_price (symbol, price) values ('RCT', 100)")
                .update();

        reconciliation.reconcile();

        assertThat(driftPairs()).isZero();
        assertThat(equity("recon-acct")).isEqualTo(800d); // 500 cash + 3 * 100 price
    }

    @Test
    @Transactional
    void forcedDivergenceRaisesDrift() {
        // settled cash with no matching ledger posting -> cash drift != 0 for (recon-acct, RCT)
        jdbc.sql("insert into risk.settled_cash (account, amount) values ('recon-acct', 1)")
                .update();
        reconciliation.reconcile();
        assertThat(driftPairs()).isGreaterThanOrEqualTo(1d);
    }
}
