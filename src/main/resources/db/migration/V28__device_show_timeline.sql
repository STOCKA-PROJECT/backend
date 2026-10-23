-- V28: the heartbeat also says which Stocka timeline the active show comes from (23-10-2026).
--
-- The Pi reads it from meta.timelineId of the package (outside the hash). With it the editor tells an old
-- version of the same timeline from another timeline. Nullable: older players do not send it and a show
-- exported outside Stocka has none. No foreign key: the timeline may be deleted while a Pi still plays it.

ALTER TABLE devices ADD COLUMN last_show_timeline_id INT NULL AFTER last_show_name;
