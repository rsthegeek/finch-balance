INSERT INTO accounts (id, user_id, balance) VALUES
    ('A', 'user-a', 100000),
    ('B', 'user-b', 50000),
    ('C', 'user-c', 0);

INSERT INTO transactions (
    transaction_id, entry_role, account_id, counterparty_account_id,
    amount, type, status, failure_code, created_at
) VALUES
    ('seed:opening:A', 'SINGLE', 'A', NULL, 100000, 'CREDIT', 'SUCCEEDED', NULL,
        TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00:00'),
    ('seed:opening:B', 'SINGLE', 'B', NULL, 50000, 'CREDIT', 'SUCCEEDED', NULL,
        TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00:00');
