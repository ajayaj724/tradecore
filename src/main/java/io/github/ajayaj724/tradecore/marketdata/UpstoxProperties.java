package io.github.ajayaj724.tradecore.marketdata;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradecore.upstox")
record UpstoxProperties(String baseUrl, String accessToken, Map<String, String> instrumentKeys) {

    UpstoxProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.upstox.com" : baseUrl;
        accessToken = accessToken == null ? "" : accessToken;
        instrumentKeys = (instrumentKeys == null || instrumentKeys.isEmpty())
                ? Map.of("ACME", "NSE_EQ|ACME", "INFY", "NSE_EQ|INE009A01021")
                : instrumentKeys;
    }
}
