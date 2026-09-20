CREATE TABLE idempotency_keys (
    key VARCHAR(128) PRIMARY KEY CHECK (key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    response_status INTEGER CHECK (response_status IN (201, 404, 422)),
    response_body TEXT,
    response_content_type VARCHAR(64),
    response_location TEXT,
    transfer_id UUID UNIQUE REFERENCES transfers (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (response_status IS NULL AND response_body IS NULL AND response_content_type IS NULL
            AND response_location IS NULL AND transfer_id IS NULL)
        OR (response_status IS NOT NULL AND response_body IS NOT NULL AND response_content_type IS NOT NULL
            AND ((response_status = 201 AND transfer_id IS NOT NULL AND response_location IS NOT NULL
                    AND response_content_type = 'application/json')
                OR (response_status IN (404, 422) AND transfer_id IS NULL AND response_location IS NULL
                    AND response_content_type = 'application/problem+json')))
    )
);

CREATE FUNCTION check_idempotency_complete() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM idempotency_keys WHERE key = NEW.key AND response_status IS NOT NULL) THEN
        RAISE EXCEPTION 'Idempotency key must have a response before commit' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER idempotency_complete AFTER INSERT ON idempotency_keys
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_idempotency_complete();

CREATE FUNCTION protect_idempotency_response() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.response_status IS NOT NULL OR NEW.key <> OLD.key OR NEW.request_hash <> OLD.request_hash
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Stored idempotency responses cannot be changed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER idempotency_response_immutable BEFORE UPDATE ON idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION protect_idempotency_response();
CREATE TRIGGER idempotency_no_delete BEFORE DELETE ON idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION reject_posted_changes();
CREATE TRIGGER idempotency_no_truncate BEFORE TRUNCATE ON idempotency_keys
    FOR EACH STATEMENT EXECUTE FUNCTION reject_posted_changes();
