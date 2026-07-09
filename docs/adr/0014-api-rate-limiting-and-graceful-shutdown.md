# ADR-0014: API-edge rate limiting and graceful shutdown

- Status: Accepted
- Date: 2026-07-09

## Context

Spec §7 calls for two runtime-hardening measures that were still missing: Bucket4j rate limiting at
the API edge, and graceful shutdown that drains in-flight orders. This ADR records both.

## Decisions

**Per-authenticated-user token-bucket rate limiting on `/api/v1/**`, via Bucket4j.** A
`HandlerInterceptor` (`RateLimitInterceptor`) keeps an in-memory `ConcurrentHashMap<principal,
Bucket>` and consumes one token per request; over-limit calls throw `RateLimitExceededException`,
rendered by `GlobalExceptionHandler` as a **429 Problem Detail** (RFC 9457, consistent with every
other error) plus a `Retry-After` header. Successful calls carry `X-Rate-Limit-Remaining`.

- **Keyed by the authenticated principal, not IP.** The interceptor runs after Spring Security, so
  the principal is available; per-user is the meaningful unit for an OMS (one trader can't starve
  others). Unauthenticated requests are already rejected by security, so the limiter no-ops on them.
- **In-memory buckets.** This is a single deployable; a distributed store (Bucket4j supports
  Hazelcast/Redis) is unwarranted until it is horizontally scaled.
- **`bucket4j_jdk17-core` 8.19.0, not `bucket4j-core`.** Bucket4j split its core per JDK; the plain
  `bucket4j-core` artifact stopped at 8.10.x, and `bucket4j_jdk17-core` (JDK 17+) is the current
  line for Java 25 (verified 2026-07-09 via repo1 maven-metadata; package stays `io.github.bucket4j`,
  8.x builder API `addLimit(l -> l.capacity(n).refillGreedy(n, period))` confirmed via Context7).
- **Policy is configurable** (`tradecore.ratelimit.capacity` / `.refill-period`, default 100/min).
  Tests set a tiny capacity in an isolated context; the shared test context uses an effectively
  unlimited capacity so unrelated ITs are never throttled.

**Graceful shutdown.** `server.shutdown: graceful` plus `spring.lifecycle.timeout-per-shutdown-phase:
20s` — on SIGTERM the web server stops accepting new requests and lets in-flight ones finish (up to
20s) before the context closes, so an order mid-flight is not dropped. Property names verified against
Boot 4.1 metadata (`server.shutdown` in `spring-boot-web-server`, the lifecycle timeout in
`spring-boot-autoconfigure` — both relocated in the Boot 4 re-modularization).

## Consequences

- The API is protected from a single credential flooding it; clients get a standards-compliant 429
  with `Retry-After`.
- Rolling restarts/deploys drain rather than sever in-flight requests.
- Rate-limit state is per-instance; horizontal scaling would need a shared bucket store (noted, not
  built). Graceful shutdown is config-level and validated by the drain mechanism rather than a unit
  test (behavioral shutdown timing is impractical to assert in the gate).
