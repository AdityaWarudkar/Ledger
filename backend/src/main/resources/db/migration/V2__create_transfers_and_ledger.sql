ALTER TABLE accounts ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'MERCHANT'
    CHECK (kind IN ('MERCHANT', 'CLEARING'));
ALTER TABLE accounts DROP CONSTRAINT accounts_balance_minor_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_balance_minor_check
    CHECK (kind = 'CLEARING' OR balance_minor >= 0);
ALTER TABLE accounts ADD CONSTRAINT accounts_id_currency_key UNIQUE (id, currency);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    from_account_id UUID NOT NULL,
    to_account_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    kind VARCHAR(16) NOT NULL DEFAULT 'TRANSFER' CHECK (kind IN ('TRANSFER', 'FUNDING')),
    status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED' CHECK (status = 'COMPLETED'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (from_account_id <> to_account_id),
    FOREIGN KEY (from_account_id, currency) REFERENCES accounts (id, currency),
    FOREIGN KEY (to_account_id, currency) REFERENCES accounts (id, currency),
    UNIQUE (id, currency)
);

CREATE INDEX transfers_created_at_id_idx ON transfers (created_at DESC, id DESC);
CREATE INDEX transfers_from_account_idx ON transfers (from_account_id, created_at DESC);
CREATE INDEX transfers_to_account_idx ON transfers (to_account_id, created_at DESC);

CREATE TABLE ledger_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id UUID NOT NULL,
    account_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor <> 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (transfer_id, currency) REFERENCES transfers (id, currency),
    FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency),
    UNIQUE (transfer_id, account_id)
);

CREATE INDEX ledger_entries_account_id_idx ON ledger_entries (account_id, id DESC);

CREATE FUNCTION reject_posted_changes() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER ledger_entries_immutable BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_posted_changes();
CREATE TRIGGER ledger_entries_no_truncate BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_posted_changes();
CREATE TRIGGER transfers_immutable BEFORE UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION reject_posted_changes();
CREATE TRIGGER transfers_no_truncate BEFORE TRUNCATE ON transfers
    FOR EACH STATEMENT EXECUTE FUNCTION reject_posted_changes();

-- A row-level CHECK cannot validate the other entry. Defer until both are written.
CREATE FUNCTION check_transfer_entries() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    transfer_row transfers%ROWTYPE;
    transfer_key UUID;
    entry_count BIGINT;
    matching_count BIGINT;
    entry_sum NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'transfers' THEN
        transfer_key := NEW.id;
    ELSE
        transfer_key := NEW.transfer_id;
    END IF;
    SELECT * INTO STRICT transfer_row FROM transfers WHERE id = transfer_key;
    SELECT count(*), coalesce(sum(amount_minor), 0),
           count(*) FILTER (WHERE
               (account_id = transfer_row.from_account_id AND amount_minor = -transfer_row.amount_minor)
               OR (account_id = transfer_row.to_account_id AND amount_minor = transfer_row.amount_minor))
      INTO entry_count, entry_sum, matching_count
      FROM ledger_entries WHERE transfer_id = transfer_key;
    IF entry_count <> 2 OR entry_sum <> 0 OR matching_count <> 2 THEN
        RAISE EXCEPTION 'Transfer % must have a matching debit and credit', transfer_key
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER transfers_balanced AFTER INSERT ON transfers
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_transfer_entries();
CREATE CONSTRAINT TRIGGER ledger_entries_balanced AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_transfer_entries();
