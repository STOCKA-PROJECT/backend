-- User-visible session record (Feature 6). One row per refresh-token family —
-- a login creates one, rotations within the family bump last_seen_at,
-- revocations from the panel (or reuse detection in the backend) flip
-- revoked_at.

CREATE TABLE IF NOT EXISTS user_devices (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    family_id VARCHAR(36) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    user_agent_raw VARCHAR(512) NULL,
    last_ip VARCHAR(45) NULL,
    first_seen_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_devices_family UNIQUE (family_id),
    CONSTRAINT fk_user_devices_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS idx_user_devices_user_revoked
    ON user_devices (user_id, revoked_at);
