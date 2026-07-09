-- Race-guard marker: an order whose cancel reached execution. If the cancel is processed before the
-- OrderAccepted (the cancel-before-accept race), submit() consults this table and refuses to rest the
-- order. Keyed by order_id so a redelivered cancel is a harmless upsert.
CREATE TABLE execution.cancelled_order (
    order_id     BIGINT PRIMARY KEY,
    cancelled_at TIMESTAMPTZ NOT NULL
);
