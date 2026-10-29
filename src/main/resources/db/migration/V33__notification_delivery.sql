-- Notification digests (2026-10-29): each member chooses how the lifecycle notifications of an
-- organization reach them (INSTANT = one email per resource, BATCHED = one digest when the
-- activity stops, DAILY = one digest per day). Events for BATCHED / DAILY recipients wait in
-- notification_outbox until the digest flusher groups them into a single email.

ALTER TABLE notification_preferences
    ADD COLUMN IF NOT EXISTS delivery VARCHAR(16) NOT NULL DEFAULT 'BATCHED';

CREATE TABLE IF NOT EXISTS notification_outbox (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    organization_id INT NOT NULL,
    resource_kind VARCHAR(32) NOT NULL,
    resource_id INT NOT NULL,
    action VARCHAR(16) NOT NULL,
    resource_name VARCHAR(255) NULL,
    actor_name VARCHAR(255) NULL,
    resource_url VARCHAR(512) NOT NULL,
    event_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notif_outbox_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_notif_outbox_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The digest flusher groups the unsent rows by recipient and organization every tick.
CREATE INDEX IF NOT EXISTS idx_notif_outbox_pending
    ON notification_outbox (sent_at, user_id, organization_id, event_at);
