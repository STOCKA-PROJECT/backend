-- Contacts: per-organization directory of external people (not registered users / not members)
-- that can own pieces. `email` is optional and its per-organization uniqueness is enforced at the
-- service layer only (no UNIQUE constraint), like a piece's serial_number, so soft-deleted rows
-- never block reusing an address. `linked_user_id` is set when the external person later joins
-- the organization and their contact is linked to the member account.
--
-- IF NOT EXISTS keeps the migration idempotent because dev runs ddl-auto=update and may have
-- already let Hibernate create the table from the entity; prod runs ddl-auto=validate and relies
-- on this migration to create it.

CREATE TABLE IF NOT EXISTS contacts (
    id INT NOT NULL AUTO_INCREMENT,
    organization_id INT NOT NULL,
    name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NULL,
    email VARCHAR(255) NULL,
    phone VARCHAR(40) NULL,
    notes TEXT NULL,
    linked_user_id INT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_contact_organization FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_contact_linked_user FOREIGN KEY (linked_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS idx_contacts_organization
    ON contacts (organization_id);

CREATE INDEX IF NOT EXISTS idx_contacts_org_email
    ON contacts (organization_id, email);

-- Pieces gain a second, mutually-exclusive owner reference: owner_user_id (existing) for members,
-- owner_contact_id for external contacts. The "at most one of the two" invariant is enforced at
-- the service layer.
ALTER TABLE pieces ADD COLUMN IF NOT EXISTS owner_contact_id INT NULL;

ALTER TABLE pieces ADD CONSTRAINT IF NOT EXISTS fk_piece_owner_contact
    FOREIGN KEY (owner_contact_id) REFERENCES contacts(id);

CREATE INDEX IF NOT EXISTS idx_piece_owner_contact
    ON pieces (owner_contact_id);
