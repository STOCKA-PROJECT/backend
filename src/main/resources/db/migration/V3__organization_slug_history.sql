-- Tracks previous slugs assigned to an organization so deep links generated before a
-- rename (typically in email notifications) can be redirected to the current slug.
CREATE TABLE IF NOT EXISTS organization_slug_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organization_id INT NOT NULL,
    old_slug VARCHAR(40) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_oslh_old_slug UNIQUE (old_slug),
    CONSTRAINT fk_oslh_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_oslh_org ON organization_slug_history (organization_id);
