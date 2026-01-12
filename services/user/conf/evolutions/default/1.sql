# --- !Ups

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    auth0_user_id VARCHAR(255) UNIQUE NOT NULL,
    firstname VARCHAR(255) NOT NULL,
    lastname VARCHAR(255) NOT NULL,
    account_type VARCHAR(10) NOT NULL,
    created_at TIMESTAMP DEFAULT now(),
    updated_at TIMESTAMP DEFAULT now(),
    
    CONSTRAINT account_type_valid CHECK (account_type IN ('BUYER', 'SELLER', 'ADMIN'))
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_auth0_user_id ON users(auth0_user_id);
CREATE INDEX idx_users_account_type ON users(account_type);

COMMENT ON COLUMN users.account_type IS 'Account type: BUYER (pays platform), SELLER (receives payouts), or ADMIN (platform administrator)';
COMMENT ON CONSTRAINT account_type_valid ON users IS 'Ensures account_type is one of: BUYER, SELLER, ADMIN';

# --- !Downs

DROP INDEX IF EXISTS idx_users_email;
DROP INDEX IF EXISTS idx_users_auth0_user_id;
DROP INDEX IF EXISTS idx_users_account_type;
DROP TABLE IF EXISTS users;
