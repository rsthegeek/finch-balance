-- Reconstruction filters by this index's account/status prefix.
CREATE INDEX idx_transactions_account_status_created
    ON transactions (account_id, status, created_at);
