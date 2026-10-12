-- V20: audio attachments for the belén show.
--
-- piece_attachments.kind gains the value AUDIO (no DDL needed: the column is a plain VARCHAR of
-- the enum name) and duration_ms stores the length the client measured when uploading, so the
-- timeline editor can size a clip before decoding the file. NULL for images and documents.
--
-- Uses IF NOT EXISTS so a partially applied run can be repeated.

ALTER TABLE piece_attachments ADD COLUMN IF NOT EXISTS duration_ms BIGINT NULL;
