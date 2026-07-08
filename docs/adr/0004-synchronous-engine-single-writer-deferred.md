# 0004: Synchronous matching engine in Phase 1B; single-writer threading deferred to Phase 3

- Status: accepted
- Date: 2026-07-08

## Context

The design spec (§4) names a single-writer-per-symbol matching engine — one owning thread per
symbol's order book, no locks in the hot path (LMAX-style) — as an architecture pillar. That
threading is a throughput/latency optimization whose value can only be justified by
measurement, and the tools to measure it (JMH microbenchmarks, Gatling load tests) are
explicitly Phase 3.

## Decision

Phase 1B ships a **synchronous** engine: `MatchingEngine.submit(...)` matches inline (guarded
by `synchronized`) behind the `ExecutionVenue` port. The single-writer-per-symbol executor is
deferred to Phase 3, where JMH/Gatling can justify and tune it. The `ExecutionVenue` port and
the `synchronized` boundary are the seam at which the engine can be made async without touching
`orders`, `risk`, or the event contracts.

## Consequences

- The engine's correctness — the four jqwik-tested invariants (no cross, no fill worse than
  limit, quantity conservation, FIFO) — is identical whether it runs synchronously or
  single-threaded-async, so deferring threading defers no correctness.
- The engine stays framework-free (`…execution.engine`, ArchUnit-enforced), keeping it
  independently benchmarkable in Phase 3.
- The order book is in-memory; durability/rebuild-from-log across restart is Phase 3 hardening.
