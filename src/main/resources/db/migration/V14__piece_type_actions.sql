-- Piece-type actions: callable functions (with typed parameters) declared per piece type, e.g.
-- "encender" with an integer parameter "tiempo". Parameters are stored as a JSON list in
-- parameters_json, mirroring how piece_type_attributes stores its validators_json blob.
--
-- IF NOT EXISTS keeps the migration idempotent because dev runs ddl-auto=update and may have
-- already let Hibernate create the table from the entity; prod runs ddl-auto=validate and relies
-- on this migration to create it.
--
-- id is @GeneratedValue(AUTO): on MariaDB Hibernate maps AUTO to a per-table sequence named
-- piece_type_actions_seq, so ddl-auto=validate in prod needs it to exist (mirroring V13). The
-- table is empty on first run, so START WITH 1 is safe; INCREMENT BY 50 matches Hibernate's
-- default allocationSize.

CREATE TABLE IF NOT EXISTS piece_type_actions (
    id INT NOT NULL AUTO_INCREMENT,
    piece_type_id INT NOT NULL,
    name VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(255) NULL,
    position INT NOT NULL DEFAULT 0,
    parameters_json TEXT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_piece_type_action_type_name UNIQUE (piece_type_id, name),
    CONSTRAINT fk_piece_type_action_type FOREIGN KEY (piece_type_id) REFERENCES piece_types(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS idx_piece_type_actions_type
    ON piece_type_actions (piece_type_id);

CREATE SEQUENCE IF NOT EXISTS piece_type_actions_seq START WITH 1 INCREMENT BY 50 NOCACHE NOCYCLE;
