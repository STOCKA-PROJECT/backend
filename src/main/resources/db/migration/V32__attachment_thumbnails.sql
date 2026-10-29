-- V32: thumbnails of IMAGE attachments (2026-10-29). The articles list of the frontend and the
-- piece catalog of the timeline editor used to download the original of every cover (phone photos
-- of several MB, up to 25 MB) to paint a thumbnail of a few pixels. The backend now generates a
-- JPEG of at most stocka.pieces.attachment.thumbnail-max-side pixels on its long side when an
-- image is uploaded (AttachmentThumbnailService) and serves it at
-- GET /organizations/{org}/pieces/{id}/attachments/{aid}/thumbnail with a one-year immutable
-- cache (attachments never change: a new file is a new row).
--
-- width/height are the pixel size of the original as displayed (EXIF orientation applied), read
-- from the file header at upload. thumb_* describe the generated JPEG; thumb_key is its object in
-- R2 (next to the original: <r2_key>.thumb.jpg). All NULL for DOCUMENT and AUDIO rows, for images
-- uploaded before this migration until POST /admin/attachments/thumbnails/backfill runs, and for
-- images the server could not decode (the endpoint then falls back to the original).

ALTER TABLE piece_attachments
    ADD COLUMN IF NOT EXISTS width INT NULL,
    ADD COLUMN IF NOT EXISTS height INT NULL,
    ADD COLUMN IF NOT EXISTS thumb_key VARCHAR(512) NULL,
    ADD COLUMN IF NOT EXISTS thumb_width INT NULL,
    ADD COLUMN IF NOT EXISTS thumb_height INT NULL,
    ADD COLUMN IF NOT EXISTS thumb_size_bytes BIGINT NULL;
