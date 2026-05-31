-- External OAuth identity links (Feature 4). One row per (user, provider)
-- once the user finishes the OAuth flow. The (provider, provider_user_id)
-- pair is globally unique — Google's "sub" claim is the stable identifier.

CREATE TABLE IF NOT EXISTS oauth_identities (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    provider VARCHAR(16) NOT NULL,
    provider_user_id VARCHAR(128) NOT NULL,
    email VARCHAR(255) NULL,
    linked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_oauth_provider_subject UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_oauth_identities_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS idx_oauth_user
    ON oauth_identities (user_id);
