package io.github.ajayaj724.tradecore.marketdata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Fetches last-traded price (paise) for a symbol from the Upstox V3 LTP endpoint. */
@Component
class UpstoxClient {

    private final RestClient rest;
    private final UpstoxProperties props;

    UpstoxClient(RestClient upstoxRestClient, UpstoxProperties props) {
        this.rest = upstoxRestClient;
        this.props = props;
    }

    long ltp(String symbol) {
        String key = props.instrumentKeys().getOrDefault(symbol, symbol);
        UpstoxLtpResponse body = rest.get()
                .uri(uri -> uri.path("/v3/market-quote/ltp")
                        .queryParam("instrument_key", key)
                        .build())
                .retrieve()
                .body(UpstoxLtpResponse.class);
        if (body == null || body.data() == null || body.data().isEmpty()) {
            throw new NoSuchElementException("no LTP for " + symbol);
        }
        // per-symbol poll -> exactly one entry; response key (":" form) != request key ("|" form)
        return toPaise(body.data().values().iterator().next().lastPrice());
    }

    static long toPaise(BigDecimal rupees) {
        return rupees.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }
}
