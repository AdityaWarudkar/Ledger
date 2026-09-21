CREATE TABLE webhook_endpoints (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL UNIQUE REFERENCES accounts(id),
    url TEXT NOT NULL,
    signing_secret VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    type VARCHAR(32) NOT NULL CHECK (type IN ('transfer.completed', 'transfer.failed')),
    transfer_id UUID UNIQUE REFERENCES transfers(id),
    request_key VARCHAR(128) UNIQUE REFERENCES idempotency_keys(key),
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((type = 'transfer.completed' AND transfer_id IS NOT NULL AND request_key IS NULL)
        OR (type = 'transfer.failed' AND transfer_id IS NULL AND request_key IS NOT NULL))
);
CREATE TRIGGER outbox_events_immutable BEFORE UPDATE OR DELETE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION reject_posted_changes();
CREATE TRIGGER outbox_events_no_truncate BEFORE TRUNCATE ON outbox_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_posted_changes();

CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES outbox_events(id),
    endpoint_id UUID NOT NULL REFERENCES webhook_endpoints(id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'DELIVERED', 'DEAD_LETTER')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    cycle_attempts INTEGER NOT NULL DEFAULT 0 CHECK (cycle_attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, endpoint_id),
    CHECK ((status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries(next_attempt_at, id)
    WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX webhook_deliveries_lease_idx ON webhook_deliveries(lease_until)
    WHERE status = 'PROCESSING';

CREATE TABLE webhook_attempts (
    delivery_id UUID NOT NULL REFERENCES webhook_deliveries(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    status_code INTEGER,
    latency_ms BIGINT CHECK (latency_ms >= 0),
    error TEXT,
    next_attempt_at TIMESTAMPTZ,
    PRIMARY KEY (delivery_id, attempt_number)
);

CREATE TABLE test_receiver_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    behavior VARCHAR(16) NOT NULL CHECK (behavior IN ('HEALTHY', 'FAIL', 'SLOW'))
);
INSERT INTO test_receiver_settings VALUES (1, 'HEALTHY');

CREATE TABLE test_receiver_receipts (
    endpoint_id UUID NOT NULL REFERENCES webhook_endpoints(id),
    event_id UUID NOT NULL,
    payload TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (endpoint_id, event_id)
);
