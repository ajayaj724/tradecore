# 0010: Resilient external market-data adapter

- Status: accepted
- Date: 2026-07-09

## Context

`marketdata.MarketDataService` today derives `last_price` from internal `TradeExecuted` events
(Phase 2B). Phase 3A adds a second, external price source — a scheduled poll of Upstox's V3 LTP
endpoint via `UpstoxClient` (Task 1) — so symbols with no recent internal trade still get a market
price. An external HTTP dependency introduces failure modes the internal event path never had:
slow responses, 5xx bursts, and outright unavailability. The client must not let those failures
propagate into the scheduled job, block a virtual-thread pool, or overwrite a good price with
nothing.

## Decision

**Guard `UpstoxClient.ltp` with Resilience4j annotations, not hand-rolled retry/circuit code.**
`@Retry(name = "upstox")` retries transient failures (`ResourceAccessException` — timeouts/I/O —
and `HttpServerErrorException` — 5xx) up to 3 attempts, 200ms apart. `@CircuitBreaker(name =
"upstox", fallbackMethod = "ltpFallback")` wraps the retried call: a count-based sliding window
(size 5, minimum 5 calls, 50% failure threshold) trips the breaker after sustained failure, holding
it open for a cooldown (`wait-duration-in-open-state`) before probing half-open.

**CircuitBreaker must be the OUTER aspect, Retry the INNER one — set explicitly via
`resilience4j.circuitbreaker.circuitbreaker-aspect-order: 1` and
`resilience4j.retry.retry-aspect-order: 2` (lower = outer).** Spring's default Resilience4j aspect
order is the other way around (`@Retry` outer, `@CircuitBreaker` inner), which is wrong for this
combination: with `fallbackMethod` on the inner `@CircuitBreaker`, the CB aspect catches the first
failure and returns the fallback value as a normal return — the outer `@Retry` aspect sees a
"successful" call and never retries, silently making `max-attempts`/`wait-duration` inert. With the
explicit order flipped (CB outer, Retry inner), a single `ltp()` call lets Retry exhaust its 3
attempts against the live upstream before the CB records the outcome as one failure — retries and
circuit-breaking compose instead of one accidentally swallowing the other. Under this ordering, an
OPEN circuit throws `CallNotPermittedException` from the outer CB before Retry ever runs, so
restricting `retry-exceptions` to `ResourceAccessException`/`HttpServerErrorException` governs only
what the inner Retry retries — an open breaker's `CallNotPermittedException` fails straight to the
fallback without the inner Retry seeing it at all, so an open breaker doesn't add retry latency on
top of its own protection.

**Retain-last-known fallback — never publish a stale price as if it were fresh.** `ltpFallback`
returns a sentinel (`UNAVAILABLE = -1L`), not the last successful value and not zero. The scheduled
poller (a later task) checks for the sentinel and, on seeing it, simply does not call
`MarketDataService` for that symbol this cycle — the existing `last_price` row is left untouched.
This is a deliberate design choice over the alternative of caching-and-replaying the last good
price from inside the client: replaying would let the adapter silently assert freshness it doesn't
have. Leaving the last row alone means the row's own age is the trust signal for any caller who
cares.

**Last-writer-wins between the two price sources.** Both the internal-trade path
(`MarketDataService.onTrade`) and the external-poll path write the same
`marketdata.last_price(symbol, price)` row via plain upsert; whichever fires more recently wins,
with no source ranking or reconciliation. This mirrors how a real market treats last-traded price:
the two sources are not in conflict, they're both "the last observed price," and Phase 2C's
`reconciliation` module (ADR-0009) is the place to detect if they drift apart, not this module.

**Annotation-driven `resilience4j-spring-boot4:2.4.0`, with `aspectjweaver` as a required,
spike-verified companion dependency, not `spring-boot-starter-aop`.** `spring-boot-starter-aop` has
no Boot-4.x GA release yet, so this project depends on `org.aspectj:aspectjweaver` directly
(version left to the Boot BOM). This is not optional: without `aspectjweaver` on the classpath, the
`@CircuitBreaker`/`@Retry` AspectJ advice silently fails to weave — no error at startup, but the
raw method body runs unguarded and its real exception propagates on every call. This was
empirically re-confirmed for this task: removing the dependency and re-running
`UpstoxResilienceIT#slowResponseTripsReadTimeoutAndFallsBack` made the test fail with the raw
`ResourceAccessException` in place of the fallback's `UNAVAILABLE` sentinel.

**A read-timeout on the synchronous `RestClient`, not `@TimeLimiter`.** Resilience4j's
`@TimeLimiter` only wraps methods returning `CompletableFuture`/reactive types; `UpstoxClient.ltp`
is a plain synchronous call, so the slow-response case is instead bounded by the
`JdkClientHttpRequestFactory` read-timeout configured in `UpstoxClientConfig` (Task 1, 2s). A 2s
read-timeout throws `ResourceAccessException`, which `@Retry`'s `retry-exceptions` list picks up
like any other transient failure — one guard (the HTTP client timeout) feeding cleanly into another
(the resilience annotations), rather than two competing timeout mechanisms.

### Recorded Boot 4.1 implementation details (carried from Task 1, restated here since they're load-bearing for this task's tests)

- `JdkClientHttpRequestFactory.setReadTimeout(Duration.ofSeconds(2))` is the JDK `HttpClient`-backed
  `ClientHttpRequestFactory` Boot 4.1 wires by default; it is what turns a stalled WireMock response
  into the `ResourceAccessException` the slow-call resilience test exercises.
- Test dependency is `org.wiremock:wiremock-standalone`, not bare `wiremock` — the core 3.x jar no
  longer bundles an embedded HTTP server, and pairing it with a `wiremock-jettyNN` extension pulled
  conflicting transitive Jetty artifacts. `wiremock-standalone` shades those away.

## Consequences

- The `"upstox"` `CircuitBreaker`/`Retry` instances are named, configured in
  `application.yaml` under `resilience4j.circuitbreaker.instances.upstox` /
  `resilience4j.retry.instances.upstox`, and registered in the shared `CircuitBreakerRegistry` —
  any later Upstox-backed client (e.g. an order-book poll) can reuse or clone this instance rather
  than inventing new resilience wiring.
- `src/test/resources/application.yaml` fully replaces `src/main/resources/application.yaml` on
  the test classpath (single-resource `classpath:` lookup, not additive across classpath roots —
  already documented at the top of that file), so the resilience4j config block had to be mirrored
  into both files. Tests that need faster recovery timing (the circuit-recovery IT) override just
  `wait-duration-in-open-state` and `permitted-number-of-calls-in-half-open-state` via
  `@DynamicPropertySource`, on top of the mirrored base config.
- The breaker is a JVM-wide singleton keyed by name (`"upstox"`) in the `CircuitBreakerRegistry`
  bean, not a per-test object — `UpstoxResilienceIT` resets it in `@BeforeEach` so the three
  fault-injection tests (slow-timeout fallback, circuit-opens-on-errors, circuit-recovers) don't
  leak state into each other regardless of run order.
- A symbol with no fresh trade and an unavailable upstream simply keeps its last stored price
  indefinitely (no expiry/staleness marker yet) — acceptable for the tracer scope; a staleness
  timestamp on `marketdata.last_price` is a natural, additive follow-up if this becomes a real feed.
- **Resilience4j aspect-order gotcha.** Spring's default aspect order makes `@Retry` inert whenever
  `fallbackMethod` sits on `@CircuitBreaker`: the default order is `@Retry` outer / `@CircuitBreaker`
  inner, so the inner CB's fallback swallows the first failure and returns normally, and the outer
  Retry never sees an exception to retry on. The explicit `circuitbreaker-aspect-order: 1` /
  `retry-aspect-order: 2` config (CB outer, Retry inner) in both `application.yaml` files is
  REQUIRED for retries to fire at all — without it, `max-attempts`, `wait-duration`, and
  `retry-exceptions` are all silently dead code. `UpstoxResilienceIT#retriesTransientFailuresBeforeFallingBack`
  guards this: it asserts WireMock received exactly 3 requests for one `ltp()` call, which fails
  (received 1) if the aspect order regresses to the default.
