-- Offline-sync foundation (Workstream A), extended to the remaining synchronizable collections:
-- pieces, piece_types, piece_type_attributes, organization_piece_attributes, piece_attachments.
-- Mirrors V15 (locations). Production schema only; dev/test rely on Hibernate ddl-auto.
--
-- rev backfill: each table is numbered independently per organization (ROW_NUMBER). Cross-table
-- rev collisions within an org are harmless because the pull cursor is per-collection. The shared
-- org_change_sequence is then advanced to the per-org maximum across ALL syncable tables, so every
-- future write receives a rev strictly greater than any existing row (DECISIONS-AND-RISKS R25/D2).

-- 1. Add nullable sync columns.
ALTER TABLE pieces                        ADD COLUMN sync_id CHAR(36) NULL, ADD COLUMN rev BIGINT NULL;
ALTER TABLE piece_types                   ADD COLUMN sync_id CHAR(36) NULL, ADD COLUMN rev BIGINT NULL;
ALTER TABLE piece_type_attributes         ADD COLUMN sync_id CHAR(36) NULL, ADD COLUMN rev BIGINT NULL;
ALTER TABLE organization_piece_attributes ADD COLUMN sync_id CHAR(36) NULL, ADD COLUMN rev BIGINT NULL;
ALTER TABLE piece_attachments             ADD COLUMN sync_id CHAR(36) NULL, ADD COLUMN rev BIGINT NULL;

-- 2. Backfill stable sync ids.
UPDATE pieces                        SET sync_id = UUID() WHERE sync_id IS NULL;
UPDATE piece_types                   SET sync_id = UUID() WHERE sync_id IS NULL;
UPDATE piece_type_attributes         SET sync_id = UUID() WHERE sync_id IS NULL;
UPDATE organization_piece_attributes SET sync_id = UUID() WHERE sync_id IS NULL;
UPDATE piece_attachments             SET sync_id = UUID() WHERE sync_id IS NULL;

-- 3. Backfill a per-organization rev (independent per table).
UPDATE pieces p
JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY organization_id ORDER BY id) AS rn FROM pieces) r
     ON r.id = p.id
SET p.rev = r.rn;

UPDATE piece_types t
JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY organization_id ORDER BY id) AS rn FROM piece_types) r
     ON r.id = t.id
SET t.rev = r.rn;

UPDATE organization_piece_attributes a
JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY organization_id ORDER BY id) AS rn
      FROM organization_piece_attributes) r
     ON r.id = a.id
SET a.rev = r.rn;

-- Tables whose organization is reached through a parent.
UPDATE piece_type_attributes a
JOIN (
    SELECT pta.id,
           ROW_NUMBER() OVER (PARTITION BY pt.organization_id ORDER BY pta.id) AS rn
    FROM piece_type_attributes pta
    JOIN piece_types pt ON pt.id = pta.piece_type_id
) r ON r.id = a.id
SET a.rev = r.rn;

UPDATE piece_attachments at
JOIN (
    SELECT att.id,
           ROW_NUMBER() OVER (PARTITION BY p.organization_id ORDER BY att.id) AS rn
    FROM piece_attachments att
    JOIN pieces p ON p.id = att.piece_id
) r ON r.id = at.id
SET at.rev = r.rn;

-- 4. Advance each organization's change sequence to the per-org maximum across all syncable tables.
INSERT INTO org_change_sequence (organization_id, seq_value)
SELECT org_id, MAX(mx) FROM (
    SELECT organization_id AS org_id, COALESCE(MAX(rev), 0) AS mx FROM locations GROUP BY organization_id
    UNION ALL
    SELECT organization_id, COALESCE(MAX(rev), 0) FROM pieces GROUP BY organization_id
    UNION ALL
    SELECT organization_id, COALESCE(MAX(rev), 0) FROM piece_types GROUP BY organization_id
    UNION ALL
    SELECT organization_id, COALESCE(MAX(rev), 0) FROM organization_piece_attributes GROUP BY organization_id
    UNION ALL
    SELECT pt.organization_id, COALESCE(MAX(pta.rev), 0)
    FROM piece_type_attributes pta JOIN piece_types pt ON pt.id = pta.piece_type_id
    GROUP BY pt.organization_id
    UNION ALL
    SELECT p.organization_id, COALESCE(MAX(att.rev), 0)
    FROM piece_attachments att JOIN pieces p ON p.id = att.piece_id
    GROUP BY p.organization_id
) u
GROUP BY org_id
ON DUPLICATE KEY UPDATE seq_value = GREATEST(org_change_sequence.seq_value, VALUES(seq_value));

-- 5. Enforce invariants and add cursor indexes.
ALTER TABLE pieces                        MODIFY COLUMN sync_id CHAR(36) NOT NULL;
ALTER TABLE piece_types                   MODIFY COLUMN sync_id CHAR(36) NOT NULL;
ALTER TABLE piece_type_attributes         MODIFY COLUMN sync_id CHAR(36) NOT NULL;
ALTER TABLE organization_piece_attributes MODIFY COLUMN sync_id CHAR(36) NOT NULL;
ALTER TABLE piece_attachments             MODIFY COLUMN sync_id CHAR(36) NOT NULL;

ALTER TABLE pieces                        ADD CONSTRAINT uk_piece_sync_id        UNIQUE (sync_id);
ALTER TABLE piece_types                   ADD CONSTRAINT uk_piece_type_sync_id   UNIQUE (sync_id);
ALTER TABLE piece_type_attributes         ADD CONSTRAINT uk_pta_sync_id          UNIQUE (sync_id);
ALTER TABLE organization_piece_attributes ADD CONSTRAINT uk_org_attr_sync_id     UNIQUE (sync_id);
ALTER TABLE piece_attachments             ADD CONSTRAINT uk_attachment_sync_id   UNIQUE (sync_id);

CREATE INDEX idx_piece_org_rev    ON pieces (organization_id, rev);
CREATE INDEX idx_piece_type_org_rev ON piece_types (organization_id, rev);
CREATE INDEX idx_org_attr_org_rev ON organization_piece_attributes (organization_id, rev);
CREATE INDEX idx_pta_rev          ON piece_type_attributes (rev);
CREATE INDEX idx_attachment_rev   ON piece_attachments (rev);
