-- Four-eyes ops cancellation (ADR-0024): an ops cancel parks a request; another ops user decides.
CREATE TABLE orders.cancel_request (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT      NOT NULL,
    requested_by TEXT        NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    status       TEXT        NOT NULL,
    decided_by   TEXT,
    decided_at   TIMESTAMPTZ,
    version      BIGINT      NOT NULL DEFAULT 0
);

-- Insert-first duplicate protection: at most one live request per order.
CREATE UNIQUE INDEX cancel_request_one_pending_per_order
    ON orders.cancel_request (order_id) WHERE status = 'PENDING';
