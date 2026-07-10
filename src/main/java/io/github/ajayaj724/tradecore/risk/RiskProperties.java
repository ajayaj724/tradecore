package io.github.ajayaj724.tradecore.risk;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradecore.risk")
record RiskProperties(Duration referenceMaxAge) {

    RiskProperties {
        referenceMaxAge = referenceMaxAge == null ? Duration.ofHours(1) : referenceMaxAge;
    }
}
