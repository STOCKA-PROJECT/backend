-- Timelines (líneas de tiempo): a named timeline per organization (unique by name within the org)
-- plus its single editor document. The document — board config, layers, placed articles and the
-- video-editor tracks/clips — is owned by the front-end editor and stored as one versioned JSON
-- blob in timeline_scenes.document; the back-end only validates structural integrity.
--
-- IF NOT EXISTS keeps the migration idempotent because dev runs ddl-auto=update and may have
-- already let Hibernate create these tables from the entities; prod runs ddl-auto=validate and
-- relies on this migration to create them.
--
-- Both ids are @GeneratedValue(AUTO): on MariaDB Hibernate maps AUTO to a per-table sequence named
-- <table>_seq, so ddl-auto=validate in prod needs timelines_seq / timeline_scenes_seq to exist
-- (mirroring V13). The tables are empty on first run, so START WITH 1 is safe; INCREMENT BY 50
-- matches Hibernate's default allocationSize.

CREATE TABLE IF NOT EXISTS timelines (
    id INT NOT NULL AUTO_INCREMENT,
    organization_id INT NOT NULL,
    name VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_timeline_org_name UNIQUE (organization_id, name),
    CONSTRAINT fk_timeline_organization FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS idx_timelines_organization
    ON timelines (organization_id);

CREATE SEQUENCE IF NOT EXISTS timelines_seq START WITH 1 INCREMENT BY 50 NOCACHE NOCYCLE;

CREATE TABLE IF NOT EXISTS timeline_scenes (
    id INT NOT NULL AUTO_INCREMENT,
    timeline_id INT NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    document LONGTEXT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_timeline_scene_timeline UNIQUE (timeline_id),
    CONSTRAINT fk_timeline_scene_timeline FOREIGN KEY (timeline_id) REFERENCES timelines(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE SEQUENCE IF NOT EXISTS timeline_scenes_seq START WITH 1 INCREMENT BY 50 NOCACHE NOCYCLE;
