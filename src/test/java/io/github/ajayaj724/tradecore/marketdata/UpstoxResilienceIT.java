package io.github.ajayaj724.tradecore.marketdata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
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
class UpstoxResilienceIT {

    static final WireMockServer WM = new WireMockServer(0); // random port

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
        // Fast recovery window so the recovery test doesn't need a long real-time wait, and so the
        // breaker (a context singleton) recovers quickly between tests.
        r.add("resilience4j.circuitbreaker.instances.upstox.wait-duration-in-open-state", () -> "1s");
        r.add("resilience4j.circuitbreaker.instances.upstox.permitted-number-of-calls-in-half-open-state", () -> "1");
    }

    private final UpstoxClient client;
    private final CircuitBreakerRegistry registry;

    @Autowired
    UpstoxResilienceIT(UpstoxClient client, CircuitBreakerRegistry registry) {
        this.client = client;
        this.registry = registry;
    }

    @BeforeEach
    void reset() {
        WM.resetAll();
        // the breaker is a context singleton shared across tests; reset it so tests are order-independent.
        registry.circuitBreaker("upstox").reset();
    }

    @Test
    void slowResponseTripsReadTimeoutAndFallsBack() {
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp"))
                .willReturn(aResponse()
                        .withFixedDelay(5000) // > 2s read timeout
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\",\"data\":{}}")));

        assertThat(client.ltp("ACME")).isEqualTo(UpstoxClient.UNAVAILABLE); // fallback sentinel
    }

    @Test
    void retriesTransientFailuresBeforeFallingBack() {
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp"))
                .willReturn(aResponse().withStatus(500)));
        assertThat(client.ltp("ACME")).isEqualTo(UpstoxClient.UNAVAILABLE);
        // CB is outer, Retry inner: one ltp() makes max-attempts (3) HTTP attempts before the CB records one failure
        verify(3, getRequestedFor(urlPathEqualTo("/v3/market-quote/ltp")));
    }

    @Test
    void repeatedServerErrorsOpenTheCircuit() {
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 5; i++) {
            assertThat(client.ltp("ACME")).isEqualTo(UpstoxClient.UNAVAILABLE);
        }

        assertThat(registry.circuitBreaker("upstox").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void circuitRecoversToClosedWhenUpstreamHealthyAgain() {
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp"))
                .willReturn(aResponse().withStatus(500)));
        for (int i = 0; i < 5; i++) {
            client.ltp("ACME");
        }
        assertThat(registry.circuitBreaker("upstox").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        WM.resetAll();
        stubFor(get(urlPathEqualTo("/v3/market-quote/ltp"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\",\"data\":{\"NSE_EQ:ACME\":"
                                + "{\"last_price\":100.00,\"instrument_token\":\"NSE_EQ|ACME\"}}}")));

        // after wait-duration (1s) the next call probes half-open; a success closes the breaker
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.ltp("ACME")).isEqualTo(10000L);
            assertThat(registry.circuitBreaker("upstox").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        });
    }
}
