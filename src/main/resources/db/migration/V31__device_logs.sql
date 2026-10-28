-- V31: log of the belén players kept in Stocka (2026-10-26). The player writes one file per day
-- (storage/logs/system-YYYY-MM-DD.log) and uploads what the backend does not have yet, by file and
-- byte offset: by itself when it has Internet (POST /player/logs, device token) or through the
-- belén app, which carries it when the player has no Internet (POST /devices/{uuid}/logs).
--
-- Each row is one uploaded piece of a file: bytes [start_offset, end_offset) of file_name. The text
-- lives gzipped in R2 under storage_key; the database only indexes it, so backups stay small.
-- (device_id, file_name, start_offset) is unique: pieces of a file never overlap because the
-- service trims what it already has before storing. log_day is the day of the file (from its name,
-- else from its first line) and drives the retention cleanup (stocka.devices.logs.retention).
--
-- device_id references the devices row (an INT), not the uuid the player generates for itself.
-- Rows outlive a soft-deleted device until the retention cleanup removes them.
--
-- id is @GeneratedValue(AUTO): on MariaDB Hibernate maps AUTO to a per-table sequence named
-- device_log_chunks_seq, so ddl-auto=validate in prod needs it (mirroring V13 and V24).

CREATE TABLE IF NOT EXISTS device_log_chunks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    device_id INT NOT NULL,
    file_name VARCHAR(80) NOT NULL,
    log_day DATE NULL,
    start_offset BIGINT NOT NULL,
    end_offset BIGINT NOT NULL,
    line_count INT NOT NULL,
    first_at DATETIME(6) NULL,
    last_at DATETIME(6) NULL,
    storage_key VARCHAR(255) NOT NULL,
    stored_bytes INT NOT NULL,
    source VARCHAR(16) NOT NULL,
    uploaded_by INT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_device_log_chunk_start UNIQUE (device_id, file_name, start_offset),
    CONSTRAINT fk_device_log_chunk_device FOREIGN KEY (device_id) REFERENCES devices(id),
    CONSTRAINT fk_device_log_chunk_user FOREIGN KEY (uploaded_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS idx_device_log_chunks_day
    ON device_log_chunks (log_day);

CREATE SEQUENCE IF NOT EXISTS device_log_chunks_seq START WITH 1 INCREMENT BY 50 NOCACHE NOCYCLE;
