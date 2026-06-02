-- Ports: per-organization hardware port definitions (Raspberry Pi GPIO), e.g. "Salida tira led 1"
-- on pin 21, related to an existing piece type (piece_type_id) and with typed parameters (channel,
-- dma) stored as a JSON list in parameters_json, mirroring how piece_type_actions stores its
-- parameters_json blob. Definitions only: there is no runtime hardware execution.
--
-- IF NOT EXISTS keeps the migration idempotent because dev runs ddl-auto=update and may have
-- already let Hibernate create the table from the entity; prod runs ddl-auto=validate and relies
-- on this migration to create it.
--
-- pin is nullable so soft-deleted rows can release the (organization_id, pin) slot by setting it to
-- NULL (InnoDB treats multiple NULLs as distinct under a UNIQUE), mirroring how the name column is
-- mangled with a "::deleted::ID" suffix to free the (organization_id, name) slot.

CREATE TABLE IF NOT EXISTS ports (
    id INT NOT NULL AUTO_INCREMENT,
    organization_id INT NOT NULL,
    name VARCHAR(160) NOT NULL,
    piece_type_id INT NOT NULL,
    pin INT NULL,
    position INT NOT NULL DEFAULT 0,
    parameters_json TEXT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_port_org_name UNIQUE (organization_id, name),
    CONSTRAINT uk_port_org_pin  UNIQUE (organization_id, pin),
    CONSTRAINT fk_port_organization FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_port_piece_type FOREIGN KEY (piece_type_id) REFERENCES piece_types(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS idx_ports_organization
    ON ports (organization_id);

CREATE INDEX IF NOT EXISTS idx_ports_piece_type
    ON ports (piece_type_id);
