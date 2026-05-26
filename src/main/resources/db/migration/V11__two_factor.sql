-- Two-factor authentication (Feature 2).
--
-- The User table grows three columns; recovery codes live in their own table
-- (one row per code, BCrypt hashed) and the setup flow lives in a third
-- short-lived table so a half-completed setup never leaves data on `users`.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS two_factor_enabled BIT(1) NOT NULL DEFAULT b'0',
    ADD COLUMN IF NOT EXISTS two_factor_secret VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS two_factor_enabled_at DATETIME(6) NULL;

CREATE TABLE IF NOT EXISTS two_factor_recovery_codes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    code_hash VARCHAR(100) NOT NULL,
    used_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_2fa_recovery_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS idx_2fa_recovery_user_used
    ON two_factor_recovery_codes (user_id, used_at);

CREATE TABLE IF NOT EXISTS two_factor_setup_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    setup_token_hash VARCHAR(64) NOT NULL,
    encrypted_secret VARCHAR(255) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed BIT(1) NOT NULL DEFAULT b'0',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_2fa_setup_token UNIQUE (setup_token_hash),
    CONSTRAINT fk_2fa_setup_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
