-- Soft-delete cascade audit (Stocka).
--
-- Schema change: add deleted_at to organization_invitations so the entity can
-- carry @SQLRestriction("deleted_at IS NULL") and cease returning rows that
-- point to a soft-deleted organization (otherwise Hibernate explodes on the
-- EAGER @ManyToOne hydration with ObjectNotFoundException).
ALTER TABLE organization_invitations
    ADD COLUMN IF NOT EXISTS deleted_at DATETIME(6) NULL;

-- Defensive create for notification_preferences. The notifications feature (PR #40)
-- shipped without a Flyway migration, so prod (ddl-auto=validate) has no such table
-- when this migration is first applied. V7 is the canonical creation, but the UPDATEs
-- below reference notification_preferences directly, so we need the table to exist
-- before V7 runs. IF NOT EXISTS keeps it a no-op on dev where Hibernate already
-- created it.
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

CREATE INDEX IF NOT EXISTS idx_organization_invitations_deleted_at
    ON organization_invitations(deleted_at);

-- Data sanitation: rows that pre-date the cascade-on-org-delete logic.
-- Idempotent thanks to the deleted_at IS NULL guard; the SoftDeleteOrphanSanitizer
-- runner reapplies the same statements on every dev start (Flyway is disabled there).

-- Invitations whose org or inviter is gone.
UPDATE organization_invitations c
   JOIN organizations p ON p.id = c.organization_id
    SET c.deleted_at = NOW()
  WHERE c.deleted_at IS NULL
    AND p.deleted_at IS NOT NULL;

UPDATE organization_invitations c
   JOIN users p ON p.id = c.invited_by_user_id
    SET c.deleted_at = NOW()
  WHERE c.deleted_at IS NULL
    AND p.deleted_at IS NOT NULL;

-- Direct children of organizations.
UPDATE pieces c
   JOIN organizations p ON p.id = c.organization_id
    SET c.deleted_at = NOW()
  WHERE c.deleted_at IS NULL
    AND p.deleted_at IS NOT NULL;

UPDATE locations c
   JOIN organizations p ON p.id = c.organization_id
    SET c.deleted_at = NOW()
  WHERE c.deleted_at IS NULL
    AND p.deleted_at IS NOT NULL;

UPDATE piece_types c
   JOIN organizations p ON p.id = c.organization_id
    SET c.deleted_at = NOW()
  WHERE c.deleted_at IS NULL
    AND p.deleted_at IS NOT NULL;

UPDATE organization_piece_attributes c
   JOIN organizations p ON p.id = c.organization_id
    SET c.deleted_at = NOW()
  WHERE c.deleted_at IS NULL
    AND p.deleted_at IS NOT NULL;

-- Grand-children: PieceTypeAttribute -> PieceType -> Organization (both fronts).
UPDATE piece_type_attributes a
   JOIN piece_types t ON t.id = a.piece_type_id
   JOIN organizations o ON o.id = t.organization_id
    SET a.deleted_at = NOW()
  WHERE a.deleted_at IS NULL
    AND o.deleted_at IS NOT NULL;

UPDATE piece_type_attributes a
   JOIN piece_types t ON t.id = a.piece_type_id
    SET a.deleted_at = NOW()
  WHERE a.deleted_at IS NULL
    AND t.deleted_at IS NOT NULL;

-- Children of pieces.
UPDATE piece_attachments c
   JOIN pieces p ON p.id = c.piece_id
    SET c.deleted_at = NOW()
  WHERE c.deleted_at IS NULL
    AND p.deleted_at IS NOT NULL;

-- Hard-deletes: tables without their own deleted_at column.
DELETE v FROM piece_attribute_values v
   JOIN pieces p ON p.id = v.piece_id
 WHERE p.deleted_at IS NOT NULL;

DELETE v FROM piece_organization_attribute_values v
   JOIN pieces p ON p.id = v.piece_id
 WHERE p.deleted_at IS NOT NULL;

DELETE t FROM email_verification_tokens t
   JOIN users u ON u.id = t.user_id
 WHERE u.deleted_at IS NOT NULL;

DELETE t FROM password_reset_tokens t
   JOIN users u ON u.id = t.user_id
 WHERE u.deleted_at IS NOT NULL;

-- NotificationPreference is bound to (user, org); both FKs can be orphaned.
UPDATE notification_preferences c
   JOIN users p ON p.id = c.user_id
    SET c.deleted_at = NOW()
  WHERE c.deleted_at IS NULL
    AND p.deleted_at IS NOT NULL;

UPDATE notification_preferences c
   JOIN organizations p ON p.id = c.organization_id
    SET c.deleted_at = NOW()
  WHERE c.deleted_at IS NULL
    AND p.deleted_at IS NOT NULL;
