package io.github.ajayaj724.tradecore.reconciliation;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradecore.reconciliation")
record ReconciliationProperties(List<String> accounts, List<String> symbols) {

    ReconciliationProperties {
        accounts = (accounts == null || accounts.isEmpty()) ? List.of("trader1", "trader2") : accounts;
        symbols = (symbols == null || symbols.isEmpty()) ? List.of("ACME") : symbols;
    }
}
