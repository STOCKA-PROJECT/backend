-- Backfills the schema for the notifications feature (PR #40). The
-- NotificationPreference and PendingResourceEvent entities were introduced
-- without a Flyway migration; dev (ddl-auto=update) created the tables
-- transparently, but prod (ddl-auto=validate) never did, so the scheduled
-- PendingResourceEventFlusher kept hitting "Table pending_resource_events
-- doesn't exist" every 15 s. IF NOT EXISTS keeps the migration idempotent
-- for environments where Hibernate already created either table.

CREATE TABLE IF NOT EXISTS notification_preferences (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    organization_id INT NOT NULL,
    pieces_actions VARCHAR(64) NOT NULL,
    piece_scope VARCHAR(16) NOT NULL,
    locations_actions VARCHAR(64) NOT NULL,
    piece_types_actions VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notif_pref_user_org UNIQUE (user_id, organization_id),
    CONSTRAINT fk_notif_pref_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_notif_pref_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS idx_notif_pref_deleted_at
    ON notification_preferences (deleted_at);

CREATE TABLE IF NOT EXISTS pending_resource_events (
    id INT NOT NULL AUTO_INCREMENT,
    organization_id INT NOT NULL,
    resource_kind VARCHAR(32) NOT NULL,
    resource_id INT NOT NULL,
    first_action VARCHAR(16) NOT NULL,
    last_action VARCHAR(16) NOT NULL,
    first_event_at DATETIME(6) NOT NULL,
    last_event_at DATETIME(6) NOT NULL,
    actor_user_id INT NULL,
    resource_name VARCHAR(255) NULL,
    owner_user_id INT NULL,
    attempts INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_pending_org_kind_res UNIQUE (organization_id, resource_kind, resource_id),
    CONSTRAINT fk_pre_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Speeds up PendingResourceEventRepository.findDueIds, which orders by
-- last_event_at every flusher tick (default every 15 s).
CREATE INDEX IF NOT EXISTS idx_pre_last_event_at
    ON pending_resource_events (last_event_at);
