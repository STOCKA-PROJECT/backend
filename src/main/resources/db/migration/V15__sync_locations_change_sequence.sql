-- Offline-sync foundation (Workstream A), scoped to the `locations` collection.
-- Adds the synchronization identity (`sync_id`) and the per-organization change-sequence
-- cursor (`rev`) to locations, plus the `org_change_sequence` counter table.
-- See docs/offline-sync/DESIGN.md and DECISIONS-AND-RISKS.md (R1, R2, R25, D1, D2).
--
-- Note: dev/test rely on Hibernate ddl-auto; this Flyway script governs production schema.

-- 1. Per-organization monotonic change sequence (the "CSN").
CREATE TABLE IF NOT EXISTS org_change_sequence (
    organization_id INT      NOT NULL,
    seq_value       BIGINT   NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_id),
    CONSTRAINT fk_org_change_sequence_org
        FOREIGN KEY (organization_id) REFERENCES organizations (id)
);

-- 2. Synchronization columns on locations (nullable first, backfilled below).
ALTER TABLE locations ADD COLUMN sync_id CHAR(36) NULL;
ALTER TABLE locations ADD COLUMN rev     BIGINT   NULL;

-- 3. Backfill stable sync ids for existing rows (R25).
UPDATE locations SET sync_id = UUID() WHERE sync_id IS NULL;

-- 4. Backfill a per-organization, gap-free rev for existing rows so the initial full pull
--    (rev > 0) returns them in a deterministic order.
UPDATE locations l
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY organization_id ORDER BY id) AS rn
    FROM locations
) ranked ON ranked.id = l.id
SET l.rev = ranked.rn;

-- 5. Seed each organization's change sequence to the highest rev handed out above, so future
--    writes continue strictly increasing.
INSERT INTO org_change_sequence (organization_id, seq_value)
SELECT organization_id, COALESCE(MAX(rev), 0)
FROM locations
GROUP BY organization_id
ON DUPLICATE KEY UPDATE seq_value = GREATEST(org_change_sequence.seq_value, VALUES(seq_value));

-- 6. Enforce invariants now that data is backfilled.
ALTER TABLE locations MODIFY COLUMN sync_id CHAR(36) NOT NULL;
ALTER TABLE locations ADD CONSTRAINT uk_location_sync_id UNIQUE (sync_id);
CREATE INDEX idx_location_org_rev ON locations (organization_id, rev);
