package io.github.ajayaj724.tradecore.marketdata;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Map;

/** Upstox V3 LTP response envelope. The {@code data} map is keyed by Upstox's response key (":" form). */
record UpstoxLtpResponse(String status, Map<String, Quote> data) {
    record Quote(
            @JsonProperty("last_price") BigDecimal lastPrice,
            @JsonProperty("instrument_token") String instrumentToken) {}
}
