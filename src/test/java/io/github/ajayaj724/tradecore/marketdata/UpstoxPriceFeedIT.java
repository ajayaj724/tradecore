package io.github.ajayaj724.tradecore.marketdata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.time.Instant;
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
class UpstoxPriceFeedIT {

    static final WireMockServer WM = new WireMockServer(0); // random port

    // 123.45 rupees -> 12345 paise; deliberately distinct from the V8 migration's seeded 10000 so the
    // assertion proves pollOnce() actually applied a new price rather than observing the seed value.
    private static final String LTP_RESPONSE_BODY = "{\"status\":\"success\",\"data\":{\"NSE_EQ:ACME\":"
            + "{\"last_price\":123.45,\"instrument_token\":\"NSE_EQ|ACME\"}}}";

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

    private final UpstoxPriceFeed feed;
    private final MarketDataService marketDataService;
    private final CircuitBreakerRegistry registry;

    @Autowired
    UpstoxPriceFeedIT(UpstoxPriceFeed feed, MarketDataService marketDataService, CircuitBreakerRegistry registry) {
        this.feed = feed;
        this.marketDataService = marketDataService;
        this.registry = registry;
    }

    @BeforeEach
    void reset() {
        WM.resetAll();
        // the breaker is a context singleton shared across tests; reset it so tests are order-independent.
        registry.circuitBreaker("upstox").reset();
    }

    @Test
    void successfulPollAppliesPriceAndPublishesUpdate() {
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(LTP_RESPONSE_BODY)));

        feed.pollOnce();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(marketDataService.lastPrice("ACME"))
                .isEqualTo(12345L));
    }

    @Test
    void degradedUpstreamLeavesLastPriceAndLastSuccessUnchanged() {
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp"))
                .willReturn(aResponse().withStatus(500)));

        long priceBefore = marketDataService.lastPrice("ACME");
        Instant lastSuccessBefore = feed.lastSuccess();

        feed.pollOnce();

        assertThat(marketDataService.lastPrice("ACME")).isEqualTo(priceBefore);
        assertThat(feed.lastSuccess()).isEqualTo(lastSuccessBefore);
    }
}
