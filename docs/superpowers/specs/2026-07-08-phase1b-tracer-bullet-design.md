# tradecore Phase 1B — Tracer Bullet: Design Spec

- **Date:** 2026-07-08
- **Status:** Approved (brainstorming) — pending implementation plan
- **Extends:** [`2026-07-06-brokerage-oms-design.md`](2026-07-06-brokerage-oms-design.md) §10, Phase 1
- **Branch:** `feat/phase1b-tracer-bullet`
- **Inputs folded in:** [`../plans/phase1b-inputs.md`](../plans/phase1b-inputs.md) (8 deferred Phase 1A findings)

## 1. Purpose & scope

Phase 1A delivered the foundation **chassis** — security, event registry, observability, and the
quality gate — with **zero business modules**. Phase 1B completes the design spec's *"Phase 1 —
walking skeleton"*: the thinnest vertical slice in which **one limit order fills end-to-end**
through security, tracing, Problem Details, audit, and idempotency — exercising `orders` + `risk`
+ `execution` + the embedded matching engine.

This is a **tracer bullet** (Hunt & Thomas), not a prototype: the thinnest possible thread through
*every* architectural layer, which then stays in the codebase and gets thickened in later phases.
The value is proving the whole path connects — security → `orders` → sync `risk` call → async
event → `execution` → engine → fill → order-state update → trace — not building any one module
fully.

### 1.1 In scope

- Three new Modulith modules: **`orders`**, **`risk`**, **`execution`** (with a framework-free
  matching engine inside `execution`).
- **LIMIT orders only**, price-time priority, **partial fills**.
- Order lifecycle: `NEW → ACCEPTED → PARTIALLY_FILLED → FILLED`, and `NEW → REJECTED`.
- Synchronous `orders → risk` pre-trade checks; `risk` owns **seeded read-models** (available cash
  + available holdings) with **race-safe reservation** (`SELECT … FOR UPDATE`).
- Event-driven execution: `OrderAccepted` → engine matches → `TradeExecuted` → `orders` advances
  order state.
- Client-supplied `Idempotency-Key`; immutable audit trail; **one OTel trace** across the whole
  thread (sync call + both async listeners).
- The **8 deferred Phase 1A findings** (see §10).

### 1.2 Out of scope — deferred deliberately, not accidents

| Deferred | Lands in | Why not now |
|---|---|---|
| Market orders | Phase 3 (engine hardening) | Separate matching path (book-walk); justified only when JMH/Gatling can measure it |
| Cancel / amend (full cancel is v1-in-scope) | Fast-follow / later | Not on the critical fill path the tracer proves |
| Single-writer-per-symbol threading | Phase 3 | Throughput optimization; only justifiable once measured. 1B engine is synchronous; the port is designed for the later swap |
| `portfolio`, `ledger`, `marketdata`, reconciliation | Phase 2 | Risk's read-models are **seeded** now, fed by their events later |
| Post-fill settlement / crediting of read-models | Phase 2 (`ledger`/`portfolio` own it) | 1B `risk` **reserves on accept only**; read-models are decremented, not re-credited on fill |

## 2. Decisions locked in brainstorming

| # | Fork | Decision | Rationale |
|---|---|---|---|
| 1 | 1B scope | **Full end-to-end fill** | Completes the spec's Phase 1 goal; proves the async event + outbox + engine path connects |
| 2 | Order/engine surface | **LIMIT-only, partial fills; all 4 invariants property-tested** | Truest thin tracer; one clean matching path; market + cancel deferred to their real phases |
| 3 | Risk data source | **Seeded read-models, both sides real, reserve on accept with row-lock** | `orders → risk` is the *only* allowed sync call — risk can never sync-call ledger/portfolio, so it keeps its own event-fed projection. Seeding it now is the real design, not a throwaway stub |
| 4 | Engine threading | **Synchronous now; single-writer async in Phase 3** | Correctness (property-tested invariants) is identical sync or async; concurrency is only justifiable once measured (JMH/Gatling, Phase 3) |

## 3. Module & package structure

Three new modules join the existing chassis. Each owns its Postgres schema; **no module reads
another's tables** (`ApplicationModules.verify()` enforces it).

```
io.github.ajayaj724.tradecore
├── config/                  (existing — security, correlation-id, Problem Details)
├── orders/                  NEW  — order lifecycle, idempotency, audit
│   ├── OrderController          (@RestController, /api/v1/orders)
│   ├── Order                    (aggregate; OrderState sealed interface; BIGINT money/qty; @Version)
│   ├── OrderService, OrderRepository  (Spring Data JDBC)
│   └── events: OrderAccepted, OrderRejected (published) ; listens TradeExecuted
├── risk/                    NEW  — pre-trade checks, seeded read-models
│   ├── RiskCheck                (exposed SYNC API — the one allowed sync call)
│   ├── AvailableCashRepository / AvailableHoldingsRepository  (SELECT … FOR UPDATE reserve)
│   └── (no controller; no event consumption in 1B)
└── execution/              NEW  — venue port + embedded engine
    ├── ExecutionVenue           (port interface)
    ├── EmbeddedMatchingVenue    (Spring adapter; listens OrderAccepted, publishes TradeExecuted)
    └── engine/                  FRAMEWORK-FREE package (ArchUnit-guarded: no Spring imports)
        ├── OrderBook, PriceLevel, MatchingEngine   (pure Java, deterministic)
        └── Fill (record)
```

The engine lives **inside** `execution` as a framework-free sub-package: the spec's 6-module map
puts the engine under `execution`, and CLAUDE.md's invariant guards it with ArchUnit ("no Spring
imports inside the engine package"). This seam is what lets Phase 3 swap synchronous → single-writer
async without touching `orders`/`risk` or the event contracts.

Inherited constraints (from CLAUDE.md + the design spec, not restated per-field below): money and
quantities are `BIGINT` minor units; no Lombok (records + compact constructors); constructor
injection only; domain events are past-tense immutable records; deterministic `Clock` injected
everywhere time is read; all errors are RFC 9457 Problem Details.

## 4. The end-to-end fill thread

```
POST /api/v1/orders  (JWT + Idempotency-Key + X-Correlation-Id)
  └─ orders: idempotency check → Order(NEW) persisted + audit row (same tx)
       └─ SYNC risk.check(account, side, symbol, price, qty)
            ├─ BUY  → available_cash ≥ price×qty ?      reserve w/ SELECT … FOR UPDATE
            └─ SELL → available_holdings[symbol] ≥ qty ? reserve w/ SELECT … FOR UPDATE
       ├─ REJECTED → Order(REJECTED) + audit + publish OrderRejected → 201 (status REJECTED)
       └─ APPROVED → Order(ACCEPTED) + audit + publish OrderAccepted (outbox, same tx) → 201 ACCEPTED
  ⇢ execution listens OrderAccepted (idempotent, dedup on event id)
       └─ ExecutionVenue.submit → engine matches (price-time, partial fills, remainder rests)
            └─ per fill: publish TradeExecuted (outbox)
  ⇢ orders listens TradeExecuted (idempotent — the duplicate-delivery-tested listener)
       └─ filledQty += qty → PARTIALLY_FILLED / FILLED  (@Version optimistic lock) + audit
```

The fill is driven by **two real crossing orders**: a resting LIMIT SELL (maker, passes
holdings-risk) posted first, then a crossing LIMIT BUY (taker, passes cash-risk) that matches it.
Both traverse `orders → risk → execution` — the whole path is exercised on both sides.

One OTel trace spans the entire thread (sync call + both async listeners); `X-Correlation-Id` is
accepted or generated and propagated.

### 4.1 Order state

`OrderState` is a **sealed interface** (`New`, `Accepted`, `Rejected`, `PartiallyFilled`, `Filled`)
— matching CLAUDE.md's "sealed interfaces for closed hierarchies (order states)" and enabling
exhaustive pattern-matching on transitions.

### 4.2 Endpoints (1B)

| Method | Path | Auth | Notes |
|---|---|---|---|
| `POST` | `/api/v1/orders` | `TRADER` | Submit LIMIT order; `Idempotency-Key` required; returns the order resource |
| `GET` | `/api/v1/orders/{id}` | `TRADER` (own) / `OPS` (all) | Observe order status; ownership check in the service layer |

**Risk rejection returns `201 Created` with `status: REJECTED`**, not a Problem Detail — a rejected
order is a *successful submission with a domain outcome*, not a fault. Problem Details are reserved
for actual faults (bad/absent JWT, unknown symbol, malformed body). This also feeds the "risk
rejection rate" metric cleanly.

## 5. Risk module & seeded read-models

`risk` exposes a single synchronous `RiskCheck` API (the one sync call the architecture permits).
It owns two projection tables in its own schema:

- `risk.available_cash` — account → available paise.
- `risk.available_holdings` — (account, symbol) → available quantity.

Both are **Flyway-seeded** for the Keycloak demo users. On an approved order, risk **reserves**
(decrements the relevant balance) inside a `SELECT … FOR UPDATE` so two orders racing for the same
balance cannot both pass — proving `rejectsBuyWhenCashInsufficient` and
`rejectsSellWhenHoldingsInsufficient`.

**Boundary rationale:** these tables are not throwaway stubs. The enduring design keeps risk's
availability view as a *local read-model fed by events* (from `ledger`/`portfolio` in Phase 2) —
risk must never synchronously call another module for balances. Seeding the same tables via Flyway
now, and swapping the *feed* to events later, is the real projection design. 1B reserves on accept
only; post-fill settlement/crediting is Phase 2 (`ledger`/`portfolio`).

## 6. Matching engine (framework-free)

Pure-Java `execution/engine/` package: no Spring imports (ArchUnit-guarded), deterministic,
injected `Clock` only where time is read. Price-time priority LIMIT book per symbol; partial fills;
unfilled remainder rests as an open resting order.

**jqwik property tests** for all four invariants (CLAUDE.md mandates property tests for engine
invariants):

1. **Book never crosses** — best bid < best ask whenever both sides rest.
2. **No fill worse than the limit** — a fill price never exceeds a buy limit / never falls below a
   sell limit.
3. **Quantity conservation** — for every order, `placed == filled + open + cancelled`.
4. **FIFO within a price level** — earlier order at the same price fills first.

The engine matches **synchronously** inside the execution listener in 1B. The `ExecutionVenue` port
is the swap point for Phase 3 single-writer-async.

## 7. Data model & migrations (Flyway, roll-forward only)

| Migration | Schema | Tables |
|---|---|---|
| `V2__orders.sql` | `orders` | `order` (id, account, symbol, side, type, price BIGINT, qty BIGINT, filled_qty BIGINT, status, version, created/updated), `idempotency` (key → order_id), `audit` (immutable who/what/when, principal) |
| `V3__risk.sql` | `risk` | `available_cash` (account → paise), `available_holdings` (account, symbol → qty) — **seeded** with demo balances |
| `V4__instruments.sql` | `orders` | `instrument` allowlist — **seeded**; `orders` validates the submitted symbol against it (unknown → Problem Detail) |

The `instrument` allowlist is **provisionally owned by `orders`** in 1B (no 7th module) — symbols
are ADMIN reference data in the full design, so this table moves to an ADMIN-owned reference module
in a later phase. Audit rows are written in the **same transaction** as the state change they
record. The engine's order book is an **in-memory** runtime projection (durability across restart
is Phase 3 hardening); orders themselves are durable in the `orders` schema.

## 8. API contract notes

- `/api/v1`, OpenAPI 3.1 (springdoc), RFC 9457 Problem Details for all faults.
- `POST /api/v1/orders` requires `Idempotency-Key`; a duplicate key returns the **original** order
  (no second submission). Semantics documented in the OpenAPI description.
- Ownership: `TRADER` sees/acts on own orders only (checked in the service layer, not just the
  endpoint); `OPS` may read all.

## 9. Testing strategy (TDD throughout)

- **Fast unit tests** — engine jqwik property tests (all 4 invariants); `Order` sealed-state
  transition tests; risk sufficiency + reservation logic.
- **Duplicate-delivery tests (mandated).** CLAUDE.md: every `@ApplicationModuleListener` ships with
  a duplicate-delivery test in the same PR. Two listeners in 1B → two tests: `execution`'s
  `OrderAccepted` consumer and `orders`' `TradeExecuted` consumer each prove re-delivery is a no-op
  (dedup on event id).
- **Testcontainers ITs** — the headline end-to-end fill; `rejectsBuyWhenCashInsufficient` /
  `rejectsSellWhenHoldingsInsufficient`; idempotency (same `Idempotency-Key` → original order, no
  double-submit); security (unauthenticated → 401 `application/problem+json`; `TRADER` cannot read
  another trader's order).
- **Module + architecture** — `ApplicationModules.verify()` green with the three new modules; new
  ArchUnit rules pass; JaCoCo ≥ 80% line held.

## 10. The 8 deferred Phase 1A findings — fold-in map

| Finding | Folds into |
|---|---|
| `spring-boot-starter-web` → `spring-boot-starter-webmvc` rename (Boot 4 relocation) | Task 1 |
| UTF-8 charset on `ProblemDetailsAuthHandlers` responses (+ test) | Task 1 |
| Test pinning `/actuator/prometheus` public + 404 `problem+json` assertion | Task 1 |
| controllers-never-touch-repositories + package-private-by-default ArchUnit rules | Task 1 (rules added) → become *meaningful* in Task 4 |
| CPD RED-proof against real domain code | Task 8 (real domain code now exists) |
| Named postgres volume (or fix `down.sh` message) | Task 1 |
| CI top-level least-privilege `permissions:` block | Task 1 |
| `.gitignore` root entry for `.superpowers/` | Task 1 |

## 11. Task breakdown (dependency order)

Detailed steps come from the writing-plans skill; this is the sequence and its rationale.

1. **Chassis cleanup & guards** — quick deferred findings + `package-private-by-default` /
   `controllers-never-touch-repositories` / `engine-no-spring` ArchUnit rules. Small, isolated,
   green.
2. **Matching engine** (framework-free) — TDD with jqwik, all 4 invariants. Fully isolated, no
   Spring → the cleanest TDD start; hardest-to-test-later piece built first while it's still pure.
3. **Instruments reference + risk module** — Flyway V3/V4, seeded read-models, `RiskCheck` sync
   API, `SELECT … FOR UPDATE` reserve. Unit + race tests.
4. **`orders` module** — `Order` sealed aggregate (BIGINT, `@Version`), idempotency, audit, Flyway
   V2, `OrderController` (POST + GET), sync risk call, publish `OrderAccepted` / `OrderRejected`.
5. **`execution` module** — `ExecutionVenue` port + `EmbeddedMatchingVenue`; consume
   `OrderAccepted` (idempotent + duplicate-delivery test); drive the engine; publish
   `TradeExecuted`.
6. **`orders` fill listener** — consume `TradeExecuted` (idempotent + duplicate-delivery test);
   advance `filledQty` / status; audit. Closes the thread.
7. **End-to-end + security ITs** — the "one order fills end-to-end" proof; risk-rejection,
   idempotency, and ownership ITs.
8. **Gate closeout** — CPD RED-proof, `ApplicationModules.verify()`, coverage, **ADRs**, README
   demo update.

Tasks 2 and 3 have no dependency on each other and could be parallelized; sequential TDD is fine.

## 12. ADRs to write (Task 8)

- **Risk owns a seeded, event-fed read-model** for cash/holdings (never a synchronous call to
  `ledger`/`portfolio`).
- **Synchronous matching engine in 1B; single-writer-per-symbol async deferred to Phase 3**, with
  the `ExecutionVenue` port as the swap point.

(Risk rejection as a `201` domain outcome rather than a Problem Detail is recorded here in §4.2; it
is a contract note, not an architectural reversal, so it stays in the spec rather than an ADR unless
review says otherwise.)

## 13. Definition of done

- One limit BUY fills end-to-end via two crossing **real** orders, through security, tracing,
  Problem Details, audit, and idempotency — proven by a Testcontainers IT.
- Both risk rejections (`rejectsBuyWhenCashInsufficient`, `rejectsSellWhenHoldingsInsufficient`)
  proven.
- Both `@ApplicationModuleListener`s have duplicate-delivery tests.
- `ApplicationModules.verify()` green; new ArchUnit rules pass; JaCoCo ≥ 80% line.
- All 8 deferred Phase 1A findings resolved.
- Two ADRs written; README 90-second demo updated to the fill flow.
- `mvn verify` green (the full machine gate).
