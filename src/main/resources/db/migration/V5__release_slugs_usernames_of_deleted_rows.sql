-- Back-fill the slug-recovery contract for rows that were soft-deleted before V3 / V4 landed.
-- After this migration:
--   * organizations.slug and users.username of soft-deleted rows are rewritten with the same
--     `__deleted_{id}_{epoch}__` marker that {@code OrganizationService.softDelete} and
--     {@code UserService.softDeleteCurrentUser} now produce. The marker contains `_`, which
--     fails the public slug/username regex, so it cannot collide with a real value.
--   * organization_slug_history rows pointing at a soft-deleted owner are removed so the
--     slugs they used previously can be claimed by anyone.
-- Newer soft-deletes already follow this contract; the migration is a one-shot cleanup.

UPDATE organizations
SET slug = CONCAT('__deleted_', id, '_', UNIX_TIMESTAMP(deleted_at), '__')
WHERE deleted_at IS NOT NULL
  AND slug NOT LIKE '\\_\\_deleted\\_%' ESCAPE '\\';

DELETE FROM organization_slug_history
WHERE organization_id IN (SELECT id FROM organizations WHERE deleted_at IS NOT NULL);

UPDATE users
SET username = CONCAT('__deleted_', id, '_', UNIX_TIMESTAMP(deleted_at), '__')
WHERE deleted_at IS NOT NULL
  AND username NOT LIKE '\\_\\_deleted\\_%' ESCAPE '\\';
