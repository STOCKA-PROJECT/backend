-- Issue #18: optimistic lock on organization_invitations to prevent double
-- acceptance creating two memberships. The @Version field maps to this column;
-- DEFAULT 0 backfills pre-existing rows so the first accept does not race.
ALTER TABLE organization_invitations
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
