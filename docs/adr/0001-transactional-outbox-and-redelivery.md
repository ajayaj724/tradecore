# 0001: Transactional outbox and redelivery for cross-module events

- Status: accepted
- Date: 2026-07-06

## Context

Modules communicate across boundaries via domain events rather than direct calls or shared
tables. Event delivery must survive process restarts and must not be lost if a listener
fails mid-processing.

## Decision

Cross-module events are published through the Spring Modulith Event Publication Registry,
which persists a transactional outbox (`event_publication`) in the same Postgres database
and transaction as the triggering write. Flyway owns the outbox schema (`V1__event_publication.sql`,
extracted verbatim from `spring-modulith-events-jdbc:2.1.0`'s `schemas/v2/schema-postgresql.sql`).
`republish-outstanding-events-on-restart` is enabled, so incomplete publications are
resubmitted on startup, giving at-least-once delivery semantics.

## Consequences

- Every `@ApplicationModuleListener` must be idempotent, since a listener may see the same
  event more than once (crash after processing but before the publication is marked
  complete, or restart-driven republish). This is enforced by duplicate-delivery tests
  starting with Plan 1B's listeners.
- Schema evolution of `event_publication` is Flyway roll-forward only, kept in lockstep
  with the Modulith JDBC module version in use.
