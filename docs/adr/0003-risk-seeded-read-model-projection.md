# 0003: Risk keeps a seeded, event-fed read-model of cash and holdings

- Status: accepted
- Date: 2026-07-08

## Context

Pre-trade risk checks need each account's available cash (for buys) and holdings (for
sells). In the target design those balances are owned by the `ledger` and `portfolio`
modules — both Phase 2. The architecture also makes `orders → risk` the **only** permitted
synchronous cross-module call, so `risk` may never synchronously call `ledger`/`portfolio`
for balances, even once they exist.

## Decision

`risk` owns two projection tables in its own schema — `risk.available_cash` and
`risk.available_holdings` — that it reads and reserves against with `SELECT … FOR UPDATE`
(reserve-on-accept: decrement on approve, no change on reject). In Phase 1B these tables are
Flyway-seeded for the demo users. In Phase 2 the *source* that maintains them switches to
domain events published by `ledger`/`portfolio` (a local read-model updated asynchronously),
never a synchronous read of another module's data.

## Consequences

- The seeded tables are the real projection, not a throwaway stub — Phase 2 changes only how
  they are fed, not their shape or ownership.
- `orders → risk` remains the single synchronous edge; the module boundary law holds.
- Phase 1B reserves on accept only; post-fill settlement/crediting of the read-models is
  Phase 2 work, so seeded balances only ever decrement during a 1B demo.
- Race safety comes from the row lock, so two orders contending for the same balance cannot
  both pass.
