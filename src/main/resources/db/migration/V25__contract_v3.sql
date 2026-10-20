-- V25: output contract v3 (20-10-2026).
--
-- The projector is gone: the kind VIDEO no longer exists in the Java enum, so any row still
-- holding it would break JPA hydration. Video ports are soft-deleted (name mangled and pin nulled,
-- as PortService does) and video piece types lose their kind (they stay as plain inventory types).
-- Piece-type actions named focoOn/focoOff (the projector's pulse) are not touched: the catalog no
-- longer knows them, so the next edit or show export reports them and the user replaces them with
-- turn_on/turn_off/pulse.
--
-- New kinds (GPIO_EXPANDER, MOVING_HEAD) need no schema change: kind is VARCHAR(32) and the
-- per-kind configuration lives in config_json.

UPDATE ports
   SET name = CONCAT(LEFT(name, 160 - CHAR_LENGTH(CONCAT('::deleted::', id))), '::deleted::', id),
       pin = NULL,
       deleted_at = COALESCE(deleted_at, NOW())
 WHERE kind = 'VIDEO'
   AND deleted_at IS NULL;

UPDATE ports
   SET kind = 'RELAY'
 WHERE kind = 'VIDEO';

UPDATE piece_types
   SET kind = NULL
 WHERE kind = 'VIDEO';
