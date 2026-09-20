CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (length(trim(name)) > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('INR', 'USD', 'EUR', 'GBP')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    balance_minor BIGINT NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX accounts_created_at_id_idx ON accounts (created_at DESC, id DESC);
