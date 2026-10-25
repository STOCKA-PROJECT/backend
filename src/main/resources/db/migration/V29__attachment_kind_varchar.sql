-- V29: piece_attachments.kind as a plain VARCHAR.
--
-- V20 assumed the column was a VARCHAR of the enum name, but databases whose schema Hibernate
-- created before Flyway took over (production, baseline 18) have a native
-- ENUM('DOCUMENT','IMAGE'), so every AUDIO upload failed with "Data truncated for column 'kind'".
-- A VARCHAR (like ports.kind) accepts new PieceAttachmentKind values without further DDL.
--
-- MODIFY is idempotent, so a repeated run is harmless.

ALTER TABLE piece_attachments MODIFY COLUMN kind VARCHAR(10) NOT NULL;
