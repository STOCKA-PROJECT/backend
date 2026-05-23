-- Soft-delete cascade audit (Stocka).
--
-- Schema change: add deleted_at to organization_invitations so the entity can
-- carry @SQLRestriction("deleted_at IS NULL") and cease returning rows that
-- point to a soft-deleted organization (otherwise Hibernate explodes on the
-- EAGER @ManyToOne hydration with ObjectNotFoundException).
ALTER TABLE organization_invitations
    ADD COLUMN IF NOT EXISTS deleted_at DATETIME(6) NULL;

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
