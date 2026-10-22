-- V27: the relay action pulse(duration) becomes double_pulse(first_press, gap, second_press) (22-10-2026).
--
-- A double press of a button: closed first_press s, open gap s, closed second_press s, then open. None
-- of the three is the clip length; they are meant to be fixed as static values of the piece-type
-- action. Existing pulse actions of RELAY piece types are renamed (display name kept) and get the
-- three parameters as static values without a value: the old duration was the clip length, so there is
-- nothing to carry over, and the show export reports E_PARAM_REQUIRED until the user fills them in.
-- Soft-deleted actions carry a mangled name (pulse::deleted::<id>) and are left alone. The legacy focoOff
-- of RELAY types is converted too, with its old timings as static values (second statement).

UPDATE piece_type_actions a
  JOIN piece_types t ON t.id = a.piece_type_id
   SET a.name = 'double_pulse',
       a.parameters_json = '[{"displayName":"1a pulsació (s)","dynamic":false,"isDuration":false,"name":"first_press","position":0,"required":true,"staticValue":null,"type":"DECIMAL","validators":null},{"displayName":"Pausa (s)","dynamic":false,"isDuration":false,"name":"gap","position":1,"required":true,"staticValue":null,"type":"DECIMAL","validators":null},{"displayName":"2a pulsació (s)","dynamic":false,"isDuration":false,"name":"second_press","position":2,"required":true,"staticValue":null,"type":"DECIMAL","validators":null}]'
 WHERE t.kind = 'RELAY'
   AND a.name = 'pulse';

-- The projector's old focoOff (hold 5 s, wait 0.05 s, press 0.1 s in the legacy RelayController) was a
-- double press, so it becomes double_pulse with those three values fixed. Skipped for a type that already
-- has a double_pulse action (UNIQUE piece_type_id + name); the derived table lets MariaDB read the table
-- it is updating. focoOn (a single 5 s hold) is left for the user to replace.
UPDATE piece_type_actions a
  JOIN piece_types t ON t.id = a.piece_type_id
   SET a.name = 'double_pulse',
       a.parameters_json = '[{"displayName":"1a pulsació (s)","dynamic":false,"isDuration":false,"name":"first_press","position":0,"required":true,"staticValue":"5","type":"DECIMAL","validators":null},{"displayName":"Pausa (s)","dynamic":false,"isDuration":false,"name":"gap","position":1,"required":true,"staticValue":"0.05","type":"DECIMAL","validators":null},{"displayName":"2a pulsació (s)","dynamic":false,"isDuration":false,"name":"second_press","position":2,"required":true,"staticValue":"0.1","type":"DECIMAL","validators":null}]'
 WHERE t.kind = 'RELAY'
   AND a.name = 'focoOff'
   AND NOT EXISTS (SELECT 1
                     FROM (SELECT piece_type_id FROM piece_type_actions WHERE name = 'double_pulse') d
                    WHERE d.piece_type_id = a.piece_type_id);
