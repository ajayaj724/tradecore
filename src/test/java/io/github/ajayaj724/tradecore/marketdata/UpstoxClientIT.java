package io.github.ajayaj724.tradecore.marketdata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@Import(TestcontainersConfig.class)
class UpstoxClientIT {

    static final WireMockServer WM = new WireMockServer(0); // random port

    private static final String LTP_RESPONSE_BODY = "{\"status\":\"success\",\"data\":{\"NSE_EQ:ACME\":"
            + "{\"last_price\":100.00,\"instrument_token\":\"NSE_EQ|ACME\"}}}";

    @BeforeAll
    static void start() {
        WM.start();
        // WireMock's static stubFor/get DSL targets a fixed admin client that defaults to
        // localhost:8080; WM binds to a random port, so it must be pointed there explicitly.
        WireMock.configureFor(WM.port());
    }

    @AfterAll
    static void stop() {
        WM.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tradecore.upstox.base-url", () -> "http://localhost:" + WM.port());
    }

    private final UpstoxClient client;

    @Autowired
    UpstoxClientIT(UpstoxClient client) {
        this.client = client;
    }

    @BeforeEach
    void reset() {
        WM.resetAll();
    }

    @Test
    void fetchesAndConvertsLtpToPaise() {
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(LTP_RESPONSE_BODY)));

        assertThat(client.ltp("ACME")).isEqualTo(10000L);
    }
}
