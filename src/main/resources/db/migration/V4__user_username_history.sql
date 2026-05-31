-- Tracks previous usernames assigned to a user so the system can decide whether the slot
-- is still tied to an active account. Mirrors organization_slug_history (V3).
CREATE TABLE IF NOT EXISTS user_username_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    old_username VARCHAR(40) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_uuh_old_username UNIQUE (old_username),
    CONSTRAINT fk_uuh_user FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_uuh_user ON user_username_history (user_id);
