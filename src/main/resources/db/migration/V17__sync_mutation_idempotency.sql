-- Idempotency store for the offline sync push endpoint (POST /sync/v1/mutations).
-- A repeated mutation_id short-circuits to a `duplicate` result so retries after a lost response
-- never re-apply a write (DECISIONS-AND-RISKS R24). Rows are pruned by age (retention must exceed
-- the client's maximum retry window). Production schema only; dev/test rely on ddl-auto.

CREATE TABLE IF NOT EXISTS sync_mutation (
    mutation_id     CHAR(36)  NOT NULL,
    organization_id INT       NOT NULL,
    applied_rev     BIGINT    NULL,
    created_at      DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (mutation_id),
    CONSTRAINT fk_sync_mutation_org
        FOREIGN KEY (organization_id) REFERENCES organizations (id)
);

CREATE INDEX idx_sync_mutation_created_at ON sync_mutation (created_at);
