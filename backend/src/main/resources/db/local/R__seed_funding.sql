INSERT INTO accounts (id, name, currency, kind, created_at) VALUES
    ('018f0000-0000-7000-8000-000000000005', 'Local funding clearing', 'INR', 'CLEARING', '2026-09-18T09:00:00Z')
ON CONFLICT (id) DO NOTHING;

-- Fixed IDs make this safe to rerun without issuing another opening credit.
DO $$
DECLARE
    funding_id UUID := '018f0000-0000-7000-8000-000000000010';
    clearing_id UUID := '018f0000-0000-7000-8000-000000000005';
    merchant_id UUID := '018f0000-0000-7000-8000-000000000001';
BEGIN
    IF NOT EXISTS (SELECT 1 FROM transfers WHERE id = funding_id) THEN
        INSERT INTO transfers (id, from_account_id, to_account_id, currency, amount_minor, kind, created_at)
        VALUES (funding_id, clearing_id, merchant_id, 'INR', 2500000, 'FUNDING', '2026-09-18T09:00:00Z');
        INSERT INTO ledger_entries (transfer_id, account_id, currency, amount_minor, created_at) VALUES
            (funding_id, clearing_id, 'INR', -2500000, '2026-09-18T09:00:00Z'),
            (funding_id, merchant_id, 'INR', 2500000, '2026-09-18T09:00:00Z');
        UPDATE accounts SET balance_minor = balance_minor - 2500000 WHERE id = clearing_id;
        UPDATE accounts SET balance_minor = balance_minor + 2500000 WHERE id = merchant_id;
    END IF;
END;
$$;
