-- V26: generic H-bridge motors and sensor ports (contract v3, 21-10-2026).
--
-- 1) Every old-shape motor port (pinR, pinL, sensorPin, downTime, maxUpTime: the angel) gets a new
--    SENSOR port "<motor>-límit" on its sensorPin, and its config is rewritten to the new shape:
--      pinL      -> pinForward       (raising was motor L)
--      pinR      -> pinReverse
--      maxUpTime -> maxTimeForward   (rows written before maxUpTime existed: downTime + 5, the old offset)
--      downTime  -> maxTimeReverse
--      the new sensor -> limitForward, only when it could be created (if its pin or name is already
--      taken by another port, INSERT IGNORE skips it and the motor is left without a limit switch:
--      it then always runs its full maxTime; the user adds the sensor by hand)
-- 2) BUTTON ports become SENSOR ports with onActive "play" (same name and pin).
-- 3) Actions of MOTOR piece types: start_up -> forward, start_down -> reverse (display name and
--    parameters kept: "time" is still the clip duration).
-- 4) Piece types of kind BUTTON (there should be none: the button was infrastructure) lose the kind.
-- 5) devices.last_alerts stores the alerts of the last heartbeat.
--
-- Rehearsed against a copy of the production database before deploying.

INSERT IGNORE INTO ports (organization_id, name, kind, piece_type_id, pin, config_json, position, parameters_json, created_at, updated_at, deleted_at)
SELECT p.organization_id,
       LEFT(CONCAT(p.name, '-límit'), 160),
       'SENSOR',
       NULL,
       CAST(JSON_VALUE(p.config_json, '$.sensorPin') AS SIGNED),
       '{}',
       p.position,
       '[]',
       NOW(6),
       NOW(6),
       NULL
  FROM ports p
 WHERE p.kind = 'MOTOR'
   AND p.deleted_at IS NULL
   AND JSON_VALUE(p.config_json, '$.sensorPin') IS NOT NULL
   AND JSON_VALUE(p.config_json, '$.pinForward') IS NULL;

UPDATE ports p
  LEFT JOIN (SELECT organization_id AS org_id, name AS sensor_name
               FROM ports
              WHERE kind = 'SENSOR' AND deleted_at IS NULL) s
         ON s.org_id = p.organization_id
        AND s.sensor_name = LEFT(CONCAT(p.name, '-límit'), 160)
   SET p.config_json = IF(s.sensor_name IS NULL,
           JSON_OBJECT(
               'pinForward', CAST(JSON_VALUE(p.config_json, '$.pinL') AS SIGNED),
               'pinReverse', CAST(JSON_VALUE(p.config_json, '$.pinR') AS SIGNED),
               'maxTimeForward', CAST(COALESCE(JSON_VALUE(p.config_json, '$.maxUpTime'), JSON_VALUE(p.config_json, '$.downTime') + 5) AS DOUBLE),
               'maxTimeReverse', CAST(JSON_VALUE(p.config_json, '$.downTime') AS DOUBLE)),
           JSON_OBJECT(
               'pinForward', CAST(JSON_VALUE(p.config_json, '$.pinL') AS SIGNED),
               'pinReverse', CAST(JSON_VALUE(p.config_json, '$.pinR') AS SIGNED),
               'maxTimeForward', CAST(COALESCE(JSON_VALUE(p.config_json, '$.maxUpTime'), JSON_VALUE(p.config_json, '$.downTime') + 5) AS DOUBLE),
               'maxTimeReverse', CAST(JSON_VALUE(p.config_json, '$.downTime') AS DOUBLE),
               'limitForward', s.sensor_name))
 WHERE p.kind = 'MOTOR'
   AND JSON_VALUE(p.config_json, '$.pinL') IS NOT NULL
   AND JSON_VALUE(p.config_json, '$.pinForward') IS NULL;

UPDATE ports
   SET kind = 'SENSOR',
       config_json = '{"onActive":"play"}'
 WHERE kind = 'BUTTON';

UPDATE piece_type_actions a
  JOIN piece_types t ON t.id = a.piece_type_id
   SET a.name = 'forward'
 WHERE t.kind = 'MOTOR'
   AND a.name = 'start_up';

UPDATE piece_type_actions a
  JOIN piece_types t ON t.id = a.piece_type_id
   SET a.name = 'reverse'
 WHERE t.kind = 'MOTOR'
   AND a.name = 'start_down';

UPDATE piece_types
   SET kind = NULL
 WHERE kind = 'BUTTON';

ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_alerts TEXT NULL;
