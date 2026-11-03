CREATE TABLE accounts (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    balance BIGINT NOT NULL,
    CONSTRAINT uq_accounts_user UNIQUE (user_id),
    CONSTRAINT ck_accounts_balance CHECK (balance >= 0)
);

CREATE TABLE transactions (
    transaction_id VARCHAR(255) NOT NULL,
    entry_role VARCHAR(16) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    counterparty_account_id VARCHAR(255),
    amount BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    failure_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (transaction_id, entry_role),
    CONSTRAINT ck_entry_role CHECK (entry_role IN ('SOURCE', 'DESTINATION', 'SINGLE')),
    CONSTRAINT ck_entry_type CHECK (type IN ('CREDIT', 'DEBIT', 'TRANSFER')),
    CONSTRAINT ck_entry_status CHECK (status IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_entry_shape CHECK (
        (type IN ('CREDIT', 'DEBIT') AND entry_role = 'SINGLE'
            AND counterparty_account_id IS NULL)
        OR (type = 'TRANSFER' AND entry_role IN ('SOURCE', 'DESTINATION')
            AND counterparty_account_id IS NOT NULL)
    ),
    CONSTRAINT ck_entry_outcome CHECK (
        (status = 'SUCCEEDED' AND failure_code IS NULL AND amount > 0)
        OR (status = 'FAILED' AND failure_code IS NOT NULL AND failure_code IN (
            'INVALID_AMOUNT', 'SAME_ACCOUNT_TRANSFER', 'ACCOUNT_NOT_FOUND',
            'INSUFFICIENT_FUNDS', 'BALANCE_OVERFLOW'))
    ),
    CONSTRAINT ck_destination_success CHECK (entry_role <> 'DESTINATION' OR status = 'SUCCEEDED'),
    CONSTRAINT ck_no_successful_self_transfer CHECK (
        status = 'FAILED' OR type <> 'TRANSFER' OR account_id <> counterparty_account_id
    )
);
