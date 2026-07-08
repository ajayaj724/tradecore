CREATE SCHEMA IF NOT EXISTS execution;

CREATE TABLE execution.processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
