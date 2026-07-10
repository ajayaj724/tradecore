package io.github.ajayaj724.tradecore.reconciliation;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradecore.reconciliation")
record ReconciliationProperties(List<String> accounts) {

    // The symbol universe is derived from marketdata.knownSymbols() so it tracks every tradable
    // instrument automatically; only the account universe stays configured.
    ReconciliationProperties {
        accounts = (accounts == null || accounts.isEmpty()) ? List.of("trader1", "trader2") : accounts;
    }
}
