-- Create ledger_account_metadata table for user information
CREATE TABLE ledger_account_metadata (
    account_id UUID PRIMARY KEY,
    user_id TEXT,
    user_email TEXT,
    display_name TEXT,
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT fk_account
        FOREIGN KEY (account_id)
        REFERENCES ledger_accounts(account_id)
        ON DELETE CASCADE
);

-- Create indexes
CREATE INDEX idx_ledger_account_metadata_user_id ON ledger_account_metadata(user_id);
CREATE INDEX idx_ledger_account_metadata_user_email ON ledger_account_metadata(user_email);

-- Add comment
COMMENT ON TABLE ledger_account_metadata IS 'Account metadata table - stores user information associated with accounts';

