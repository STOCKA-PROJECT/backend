-- V19: output kinds for the belén contract (piece types + ports).
--
-- Adds the closed `kind` enum (stored as the Java constant name, hence 'RELAY') to piece types
-- and ports, a per-kind JSON config blob on ports, and makes ports.piece_type_id nullable so
-- infrastructure ports (LED strips, button) can exist without a piece type.
--
-- Existing ports become RELAY (the only kind that matches "one pin, no config"); the UPDATE below
-- keeps the "port.kind == piece_type.kind" invariant for the types those ports reference. Any
-- existing port that was really a LED strip must be re-typed by hand: it cannot be inferred.
--
-- Uses IF NOT EXISTS so a partially applied run can be repeated; MODIFY COLUMN is idempotent.

ALTER TABLE piece_types ADD COLUMN IF NOT EXISTS kind VARCHAR(32) NULL;

ALTER TABLE ports ADD COLUMN IF NOT EXISTS kind VARCHAR(32) NOT NULL DEFAULT 'RELAY';
ALTER TABLE ports ADD COLUMN IF NOT EXISTS config_json TEXT NULL;
ALTER TABLE ports MODIFY COLUMN piece_type_id INT NULL;

UPDATE piece_types pt
   SET pt.kind = 'RELAY'
 WHERE pt.kind IS NULL
   AND EXISTS (SELECT 1 FROM ports p WHERE p.piece_type_id = pt.id AND p.deleted_at IS NULL);
