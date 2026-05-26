-- Refresh tokens (Feature 1). Stored as SHA-256 hex hashes — the raw value only
-- ever lives in the httpOnly stocka_refresh cookie. family_id groups every rotation
-- of a single login so reuse detection can wipe the whole chain at once.
--
-- IF NOT EXISTS keeps the migration idempotent because dev runs ddl-auto=update
-- and may have already let Hibernate create the table from the entity.

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id INT NOT NULL AUTO_INCREMENT,
    token_hash VARCHAR(64) NOT NULL,
    user_id INT NOT NULL,
    family_id VARCHAR(36) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    revoked_reason VARCHAR(32) NULL,
    replaced_by_hash VARCHAR(64) NULL,
    remember_me BIT(1) NOT NULL DEFAULT b'0',
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_revoked
    ON refresh_tokens (user_id, revoked_at);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family
    ON refresh_tokens (family_id);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at
    ON refresh_tokens (expires_at);
