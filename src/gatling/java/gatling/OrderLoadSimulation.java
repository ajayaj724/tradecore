package gatling;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Phase 3B load test: sustained authenticated order submission against the real
 * {@code POST /api/v1/orders} path (security, risk, matching engine, response) with published SLO
 * assertions. Run against a live app + platform:
 *
 * <pre>{@code
 *   scripts/up.sh    # Postgres + Keycloak
 *   scripts/run.sh   # app on :8080
 *   mvn -Pgatling gatling:test -Dgatling.simulationClass=gatling.OrderLoadSimulation
 * }</pre>
 *
 * Everything is overridable with {@code -D} system properties (see the constants), so the same file
 * drives a laptop smoke run or a larger soak. SLO thresholds default to the calibrated values in
 * ADR-0011 and fail the run when breached.
 */
public class OrderLoadSimulation extends Simulation {

    private static final String APP = System.getProperty("tradecore.baseUrl", "http://localhost:8080");
    private static final String KC = System.getProperty("tradecore.kcUrl", "http://localhost:8081");
    private static final String REALM = System.getProperty("tradecore.realm", "tradecore");
    private static final String CLIENT = System.getProperty("tradecore.client", "tradecore-api");
    private static final String USER = System.getProperty("tradecore.user", "trader1");
    private static final String PASS = System.getProperty("tradecore.pass", "demo");

    private static final int RATE = Integer.getInteger("tradecore.rate", 50);
    private static final int DURATION_SEC = Integer.getInteger("tradecore.durationSec", 60);

    // Published SLOs (ADR-0011), calibrated on the reference local stack. Override per-run with -D.
    private static final int SLO_P50_MS = Integer.getInteger("tradecore.slo.p50ms", 25);
    private static final int SLO_P99_MS = Integer.getInteger("tradecore.slo.p99ms", 100);
    private static final double SLO_MAX_FAILED_PCT = failedPctSlo();

    private final String token = fetchAccessToken();

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(APP)
            .authorizationHeader("Bearer " + token)
            .contentTypeHeader("application/json")
            .acceptHeader("application/json")
            .shareConnections();

    // Infinite feeder: a unique Idempotency-Key per request (each is a genuine new order, never a
    // dedup replay) and a 50/50 BUY/SELL mix that crosses at a common price to exercise the engine.
    private final Iterator<Map<String, Object>> orders = Stream.generate(OrderLoadSimulation::nextOrder)
            .iterator();

    private final ScenarioBuilder submitOrders = scenario("submit-orders")
            .feed(orders)
            .exec(http("POST /api/v1/orders")
                    .post("/api/v1/orders")
                    .header("Idempotency-Key", "#{idem}")
                    .body(StringBody("{\"symbol\":\"ACME\",\"side\":\"#{side}\",\"price\":10000,\"quantity\":5}"))
                    // 201 covers both ACCEPTED and the domain REJECTED outcome; anything else is a fault.
                    .check(status().is(201)));

    {
        setUp(submitOrders.injectOpen(constantUsersPerSec(RATE).during(Duration.ofSeconds(DURATION_SEC))))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lte(SLO_MAX_FAILED_PCT),
                        global().responseTime().percentile1().lt(SLO_P50_MS),
                        global().responseTime().percentile4().lt(SLO_P99_MS));
    }

    private static Map<String, Object> nextOrder() {
        Map<String, Object> row = new HashMap<>();
        row.put("idem", UUID.randomUUID().toString());
        row.put("side", ThreadLocalRandom.current().nextBoolean() ? "BUY" : "SELL");
        return row;
    }

    private static double failedPctSlo() {
        return Double.parseDouble(System.getProperty("tradecore.slo.failedPct", "0.1"));
    }

    /** One password-grant token, fetched once and shared by all virtual users (all act as USER). */
    private static String fetchAccessToken() {
        String form = "grant_type=password&client_id=" + CLIENT + "&username=" + USER + "&password=" + PASS;
        URI tokenUri = URI.create(KC + "/realms/" + REALM + "/protocol/openid-connect/token");
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(tokenUri)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Matcher m = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(resp.body());
            if (resp.statusCode() != 200 || !m.find()) {
                throw new IllegalStateException(
                        "token request to " + tokenUri + " returned HTTP " + resp.statusCode() + " with no token");
            }
            return m.group(1);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("token request to " + tokenUri + " failed — is the platform up?", e);
        }
    }
}
