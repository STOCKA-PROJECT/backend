package com.stocka.backend.modules.devices.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.devices.config.DevicesProperties;
import com.stocka.backend.modules.devices.entity.Device;
import com.stocka.backend.modules.devices.entity.DeviceLogChunk;
import com.stocka.backend.modules.devices.entity.DeviceLogChunk.Source;
import com.stocka.backend.modules.devices.repository.DeviceLogChunkRepository;
import com.stocka.backend.modules.devices.repository.DeviceLogChunkRepository.FileSummary;
import com.stocka.backend.modules.devices.repository.DeviceRepository;
import com.stocka.backend.modules.storage.R2Service;

/**
 * The players' log kept in Stocka. A player writes one file per day ({@code system-YYYY-MM-DD.log})
 * and its log reaches the backend by file and byte offset, either from the player itself or carried
 * by the belén app: {@link #cursor} says how far each file is stored and {@link #append} adds the
 * next piece. Appending is idempotent: what is already stored is trimmed, a piece that would leave
 * a gap is refused with the offset to resume from. Pieces are stored gzipped in R2; the
 * {@code device_log_chunks} rows only index them.
 *
 * <p>Two uploads for the same player (the player and an app at once) are serialized by locking the
 * device row, so pieces never overlap. The lock is held while the piece goes to R2 (at most 2 MiB,
 * a few seconds): a heartbeat of that player arriving meanwhile waits for it, which is fine at this
 * volume. Pieces older than {@code stocka.devices.logs.retention} are deleted every night by
 * {@link #purgeExpired()}.
 */
@Service
public class DeviceLogService {
    /** A plain file name ending in {@code .log}: no directories, no leading dot. */
    static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,75}\\.log");
    /** MIME type of the stored pieces. */
    static final String GZIP = "application/gzip";

    private static final Logger log = LoggerFactory.getLogger(DeviceLogService.class);
    private static final Pattern DAY_IN_NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern LINE_TIME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})", Pattern.MULTILINE);
    private static final int COPY_BUFFER = 64 * 1024;

    private final DeviceLogChunkRepository chunkRepository;
    private final DeviceRepository deviceRepository;
    private final R2Service r2;
    private final DevicesProperties.Logs settings;
    private final Clock clock;

    @Autowired
    public DeviceLogService(DeviceLogChunkRepository chunkRepository, DeviceRepository deviceRepository, R2Service r2,
                            DevicesProperties properties) {
        this(chunkRepository, deviceRepository, r2, properties, Clock.systemDefaultZone());
    }

    DeviceLogService(DeviceLogChunkRepository chunkRepository, DeviceRepository deviceRepository, R2Service r2,
                     DevicesProperties properties, Clock clock) {
        this.chunkRepository = chunkRepository;
        this.deviceRepository = deviceRepository;
        this.r2 = r2;
        this.settings = properties.getLogs();
        this.clock = clock;
    }

    /**
     * How far each file of a device is stored, and the oldest day still kept.
     *
     * @param files end offset by file name
     * @param since first day the retention keeps: older files are not worth uploading
     */
    public record Cursor(Map<String, Long> files, LocalDate since) { }

    /**
     * Outcome of {@link #append}.
     *
     * @param file   file name
     * @param end    end offset the backend now has for the file
     * @param stored bytes of the upload that were new (0 when everything was already stored)
     */
    public record AppendResult(String file, long end, long stored) { }

    /**
     * How far each file of the device is stored.
     *
     * @param device the player
     * @return end offset per file and the oldest day kept
     */
    @Transactional(readOnly = true)
    public Cursor cursor(Device device) {
        Map<String, Long> files = new TreeMap<>();
        for (Object[] row : chunkRepository.endOffsets(device.getId())) {
            files.put((String) row[0], ((Number) row[1]).longValue());
        }
        return new Cursor(files, oldestKeptDay());
    }

    /**
     * Stores the bytes {@code [from, from + bytes.length)} of a log file: the part the backend
     * already has is dropped, the rest becomes a new piece.
     *
     * @param device     the player the log comes from
     * @param fileName   file name, e.g. {@code system-2026-10-26.log}
     * @param from       offset of the first byte in the file
     * @param bytes      the bytes, at most {@code max-chunk-bytes}
     * @param source     who uploads
     * @param uploadedBy user carrying the log, {@code null} for the player itself
     * @return how far the file is stored now
     * @throws ApiException 400 {@link ErrorCodes#DEVICES_LOG_FILE_INVALID} for a bad name or a
     *                      negative offset; 413 {@link ErrorCodes#DEVICES_LOG_TOO_LARGE}; 409
     *                      {@link ErrorCodes#DEVICES_LOG_OFFSET_MISMATCH} (param {@code expected})
     *                      when the piece would leave a gap
     */
    @Transactional
    public AppendResult append(Device device, String fileName, long from, byte[] bytes, Source source, Integer uploadedBy) {
        requireFileName(fileName);
        if (from < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEVICES_LOG_FILE_INVALID);
        }
        if (bytes.length > settings.getMaxChunkBytes()) {
            throw tooLarge();
        }
        Device locked = deviceRepository.lockById(device.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DEVICES_NOT_FOUND));
        long end = endOf(locked.getId(), fileName);
        if (from > end) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DEVICES_LOG_OFFSET_MISMATCH, Map.of("expected", end));
        }
        long to = from + bytes.length;
        if (to <= end) {
            return new AppendResult(fileName, end, 0);
        }
        byte[] piece = Arrays.copyOfRange(bytes, (int) (end - from), bytes.length);
        String text = new String(piece, StandardCharsets.UTF_8);
        byte[] gz = gzip(piece);
        String key = "device-logs/" + locked.getId() + "/" + fileName + "/" + end + "-" + to + ".log.gz";
        r2.upload(key, new ByteArrayInputStream(gz), gz.length, GZIP);

        DeviceLogChunk chunk = new DeviceLogChunk()
                .setDeviceRowId(locked.getId())
                .setFileName(fileName)
                .setLogDay(dayOf(fileName, text))
                .setStartOffset(end)
                .setEndOffset(to)
                .setLineCount(countLines(piece))
                .setFirstAt(lineTime(text, true))
                .setLastAt(lineTime(text, false))
                .setStorageKey(key)
                .setStoredBytes(gz.length)
                .setSource(source)
                .setUploadedBy(uploadedBy);
        try {
            chunkRepository.saveAndFlush(chunk);
        } catch (DataIntegrityViolationException ex) {
            deleteQuietly(key);
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DEVICES_LOG_OFFSET_MISMATCH, Map.of("expected", end), ex);
        }
        return new AppendResult(fileName, to, piece.length);
    }

    /**
     * Reads at most {@code max-chunk-bytes} of an upload, refusing a larger one without reading it all.
     *
     * @param in the request body
     * @return the bytes
     * @throws IOException  when reading fails
     * @throws ApiException 413 {@link ErrorCodes#DEVICES_LOG_TOO_LARGE} when the body is larger
     */
    public byte[] readUpload(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(settings.getMaxChunkBytes() + 1);
        if (bytes.length > settings.getMaxChunkBytes()) {
            throw tooLarge();
        }
        return bytes;
    }

    /**
     * The files of a device kept in Stocka, newest day first.
     *
     * @param device the player
     * @return one summary per file
     */
    @Transactional(readOnly = true)
    public List<FileSummary> files(Device device) {
        return chunkRepository.summaries(device.getId());
    }

    /**
     * The pieces of a file, checked to exist before anything is streamed.
     *
     * @param device   the player
     * @param fileName file name
     * @return pieces in order
     * @throws ApiException 400 for a bad name; 404 {@link ErrorCodes#DEVICES_LOG_NOT_FOUND} when the
     *                      file has nothing stored
     */
    @Transactional(readOnly = true)
    public List<DeviceLogChunk> fileChunks(Device device, String fileName) {
        requireFileName(fileName);
        List<DeviceLogChunk> chunks = chunkRepository.findByDeviceRowIdAndFileNameOrderByStartOffsetAsc(device.getId(), fileName);
        if (chunks.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DEVICES_LOG_NOT_FOUND);
        }
        return chunks;
    }

    /**
     * The pieces of the files of a period, checked before anything is streamed.
     *
     * @param device the player
     * @param from   first day, inclusive
     * @param to     last day, inclusive
     * @return pieces ordered by day, file and offset
     * @throws ApiException 400 {@link ErrorCodes#DEVICES_LOG_RANGE_INVALID} for a reversed or too
     *                      long period; 404 when nothing is stored in it
     */
    @Transactional(readOnly = true)
    public List<DeviceLogChunk> periodChunks(Device device, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= settings.getMaxArchiveDays()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEVICES_LOG_RANGE_INVALID, Map.of("maxDays", settings.getMaxArchiveDays()));
        }
        List<DeviceLogChunk> chunks = chunkRepository.findInPeriod(device.getId(), from, to);
        if (chunks.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DEVICES_LOG_NOT_FOUND);
        }
        return chunks;
    }

    /**
     * Writes a file as the player wrote it, or only its last lines.
     *
     * @param chunks pieces of the file in order ({@link #fileChunks})
     * @param tail   how many last lines, or {@code null} for the whole file (capped at {@code max-tail-lines})
     * @param out    where to write
     * @throws IOException when writing fails
     */
    public void writeFile(List<DeviceLogChunk> chunks, Integer tail, OutputStream out) throws IOException {
        if (tail == null) {
            for (DeviceLogChunk chunk : chunks) {
                copyPiece(chunk, out, 0);
            }
            return;
        }
        int wanted = Math.max(1, Math.min(tail, settings.getMaxTailLines()));
        int first = chunks.size();
        long lines = 0;
        while (first > 0 && lines < wanted) {
            first--;
            lines += chunks.get(first).getLineCount();
        }
        long skip = Math.max(0, lines - wanted);
        for (DeviceLogChunk chunk : chunks.subList(first, chunks.size())) {
            skip = copyPiece(chunk, out, skip);
        }
    }

    /**
     * Writes a zip with one entry per file of the period.
     *
     * @param chunks pieces of the period ({@link #periodChunks})
     * @param out    where to write
     * @throws IOException when writing fails
     */
    public void writeArchive(List<DeviceLogChunk> chunks, OutputStream out) throws IOException {
        Map<String, List<DeviceLogChunk>> byFile = new LinkedHashMap<>();
        for (DeviceLogChunk chunk : chunks) {
            byFile.computeIfAbsent(chunk.getFileName(), k -> new ArrayList<>()).add(chunk);
        }
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, List<DeviceLogChunk>> file : byFile.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                for (DeviceLogChunk chunk : file.getValue()) {
                    copyPiece(chunk, zip, 0);
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * Deletes every piece older than the retention, with its object in R2. Runs every night.
     *
     * @return how many pieces were deleted
     */
    @Scheduled(cron = "${stocka.devices.logs.cleanup-cron:0 40 4 * * *}")
    @Transactional
    public int purgeExpired() {
        List<DeviceLogChunk> expired = chunkRepository.findByLogDayBefore(oldestKeptDay());
        if (expired.isEmpty()) {
            return 0;
        }
        expired.forEach(chunk -> deleteQuietly(chunk.getStorageKey()));
        chunkRepository.deleteAll(expired);
        log.info("device_logs_purged pieces={}", expired.size());
        return expired.size();
    }

    // ------------------------------------------------------------------ helpers

    private LocalDate oldestKeptDay() {
        return LocalDate.now(clock).minusDays(settings.getRetention().toDays());
    }

    private long endOf(Integer deviceRowId, String fileName) {
        return chunkRepository.endOffsets(deviceRowId).stream()
                .filter(row -> fileName.equals(row[0]))
                .mapToLong(row -> ((Number) row[1]).longValue())
                .findFirst()
                .orElse(0L);
    }

    private static void requireFileName(String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEVICES_LOG_FILE_INVALID);
        }
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCodes.DEVICES_LOG_TOO_LARGE, Map.of("max", settings.getMaxChunkBytes()));
    }

    /** The day of a file: from its name, else from its first line, else today. */
    private LocalDate dayOf(String fileName, String text) {
        Matcher name = DAY_IN_NAME.matcher(fileName);
        if (name.find()) {
            try {
                return LocalDate.parse(name.group(1));
            } catch (DateTimeParseException ignored) {
                // not a date after all: fall back to the content
            }
        }
        LocalDateTime first = lineTime(text, true);
        return first != null ? first.toLocalDate() : LocalDate.now(clock);
    }

    /** The time of the first or last line that starts with {@code YYYY-MM-DD HH:MM:SS}. */
    private static LocalDateTime lineTime(String text, boolean first) {
        Matcher m = LINE_TIME.matcher(text);
        LocalDateTime found = null;
        while (m.find()) {
            try {
                found = LocalDateTime.parse(m.group(1) + "T" + m.group(2));
            } catch (DateTimeParseException ignored) {
                continue;
            }
            if (first) {
                return found;
            }
        }
        return found;
    }

    private static int countLines(byte[] bytes) {
        int lines = 0;
        for (byte b : bytes) {
            if (b == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static byte[] gzip(byte[] bytes) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, bytes.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(buffer)) {
            gz.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    /**
     * Copies a piece to {@code out}, first dropping {@code skipLines} whole lines.
     *
     * @return the lines still to skip after this piece
     */
    private long copyPiece(DeviceLogChunk chunk, OutputStream out, long skipLines) throws IOException {
        try (InputStream in = new GZIPInputStream(r2.open(chunk.getStorageKey()), COPY_BUFFER)) {
            byte[] buffer = new byte[COPY_BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                int start = 0;
                while (skipLines > 0 && start < read) {
                    if (buffer[start++] == '\n') {
                        skipLines--;
                    }
                }
                if (start < read) {
                    out.write(buffer, start, read - start);
                }
            }
        }
        return skipLines;
    }

    private void deleteQuietly(String key) {
        try {
            r2.delete(key);
        } catch (RuntimeException ex) {
            log.warn("device_log_delete_failed key={}", key, ex);
        }
    }
}
