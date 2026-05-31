-- Security audit log (Feature 3). Append-only record of login attempts,
-- password changes, refresh-token rotation, 2FA activation, etc.
--
-- Written asynchronously by SecurityAuditListener; the per-event metadata
-- column intentionally stores raw JSON (no relational join) so adding new
-- event types never needs a schema migration.

CREATE TABLE IF NOT EXISTS security_audit_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id INT NULL,
    email VARCHAR(255) NULL,
    event_type VARCHAR(64) NOT NULL,
    ip_address VARCHAR(45) NULL,
    user_agent VARCHAR(512) NULL,
    metadata TEXT NULL,
    success BIT(1) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_security_audit_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS idx_security_audit_user_created
    ON security_audit_entries (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_security_audit_event_created
    ON security_audit_entries (event_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_security_audit_created
    ON security_audit_entries (created_at);
