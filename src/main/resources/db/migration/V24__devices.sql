-- Devices: the Raspberry Pi players ("belenes") of an organization, registered in Stocka so each
-- one can report a periodic heartbeat (POST /player/heartbeat) whenever it happens to have
-- Internet. The heartbeat is optional telemetry: the player works fully without it. Each row
-- stores the latest report (last_* columns) and the sha256 of the heartbeat token the Pi presents
-- as "Authorization: Device <token>"; the raw token is shown once at creation/rotation and never
-- stored.
--
-- IF NOT EXISTS keeps the migration idempotent because dev runs ddl-auto=update and may have
-- already let Hibernate create the table from the entity; prod runs ddl-auto=validate and relies
-- on this migration to create it.
--
-- device_id is the uuid the Pi generates for itself; it is NULL until the first heartbeat binds
-- it. name is unique per organization; on soft delete it is mangled with a "::deleted::ID" suffix
-- (like ports) so the slot is freed. token_hash is unique across all rows because the lookup on
-- heartbeat is by hash alone, without an organization.
--
-- id is @GeneratedValue(AUTO): on MariaDB Hibernate maps AUTO to a per-table sequence named
-- devices_seq, so ddl-auto=validate in prod needs it to exist (mirroring V13). The table is empty
-- on first run, so START WITH 1 is safe; INCREMENT BY 50 matches Hibernate's default
-- allocationSize.

CREATE TABLE IF NOT EXISTS devices (
    id INT NOT NULL AUTO_INCREMENT,
    organization_id INT NOT NULL,
    device_id VARCHAR(36) NULL,
    name VARCHAR(80) NOT NULL,
    board VARCHAR(32) NOT NULL DEFAULT 'rpi4b',
    token_hash CHAR(64) NOT NULL,
    token_prefix VARCHAR(8) NOT NULL,
    last_seen_at DATETIME(6) NULL,
    last_state VARCHAR(16) NULL,
    last_show_hash VARCHAR(80) NULL,
    last_show_name VARCHAR(160) NULL,
    last_overrides TEXT NULL,
    last_version VARCHAR(40) NULL,
    last_ip VARCHAR(45) NULL,
    last_error TEXT NULL,
    last_net_mode VARCHAR(8) NULL,
    last_uptime_s BIGINT NULL,
    created_by INT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_device_org_name UNIQUE (organization_id, name),
    CONSTRAINT uk_device_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_device_organization FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_device_created_by FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS idx_devices_organization
    ON devices (organization_id);

CREATE SEQUENCE IF NOT EXISTS devices_seq START WITH 1 INCREMENT BY 50 NOCACHE NOCYCLE;
