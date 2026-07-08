# 0005: Phase 1B design refinements

- Status: accepted
- Date: 2026-07-08

## Context

Turning the Phase 1B tracer-bullet spec into working code surfaced four decisions that the
spec left open or that refine what it stated. They are recorded here together.

## Decision

1. **Shared contracts module.** Cross-boundary event records (`OrderAccepted`, `OrderRejected`,
   `TradeExecuted`) and the `Side` enum live in a `shared` module that every business module
   depends on. The tracer's event flow is bidirectional (`orders` → `OrderAccepted` →
   `execution` → `TradeExecuted` → `orders`), and only `orders` may mutate the `Order`
   aggregate, so that back-edge is unavoidable. An `@ApplicationModuleListener` on another
   module's type is a real dependency edge, so events owned by their producers would make
   `ApplicationModules.verify()` report a cycle. Fanning the contracts into `shared` keeps the
   graph acyclic. This refines the spec's "published events = the publishing module's API" for
   the round-trip case; it is a recognized Spring Modulith pattern.

2. **`OrderStatus` enum + sealed `RiskDecision`** rather than the spec's sealed `OrderState`.
   An enum is a closed hierarchy with exhaustive `switch` and persists as a single `status`
   column; the sealed interface is used where a variant carries data (`RiskDecision.Rejected`
   carries a reason), which is where it earns its keep.

3. **Account identity = JWT `preferred_username`.** The Keycloak realm users have no fixed
   `id`, so their `sub` changes on every realm re-import (`--wipe`). `preferred_username` is
   the stable key; read-models are seeded by it and integration tests use mock JWTs carrying it.

4. **Migrations numbered by build order** — `V2` risk, `V3` orders, `V4` execution — because
   Flyway is roll-forward only and versions must be added in ascending order across the tasks
   that introduce them (risk is built before orders, which depends on it).

## Consequences

- `shared` is a fan-in module (nothing depends back on the business modules), so no cycle.
- The persisted order shape stays a flat row; pattern matching happens over the enum and over
  `RiskDecision`.
- Seeds and tests key on `preferred_username`, independent of Keycloak's generated subjects.
- The `instrument` allowlist is provisionally owned by `orders` in 1B and moves to an
  ADMIN-owned reference module in a later phase.
