package com.stocka.backend.modules.devices.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.devices.entity.DeviceLogChunk;

/**
 * Data access for {@link DeviceLogChunk}: the pieces of the players' log files kept in Stocka.
 */
@Repository
public interface DeviceLogChunkRepository extends JpaRepository<DeviceLogChunk, Long> {

    /**
     * Per file of a device, how far the backend has it: the end offset of its last piece.
     *
     * @param deviceRowId device row id
     * @return {@code [fileName, maxEndOffset]} pairs
     */
    @Query("select c.fileName, max(c.endOffset) from DeviceLogChunk c where c.deviceRowId = ?1 group by c.fileName")
    List<Object[]> endOffsets(Integer deviceRowId);

    /**
     * Summary of every file of a device, newest day first.
     *
     * @param deviceRowId device row id
     * @return one projection per file
     */
    @Query("select c.fileName as fileName, max(c.logDay) as logDay, max(c.endOffset) as size, sum(c.lineCount) as lines, "
            + "min(c.firstAt) as firstAt, max(c.lastAt) as lastAt, max(c.createdAt) as syncedAt "
            + "from DeviceLogChunk c where c.deviceRowId = ?1 group by c.fileName order by max(c.logDay) desc, c.fileName desc")
    List<FileSummary> summaries(Integer deviceRowId);

    /**
     * The pieces of one file in order.
     *
     * @param deviceRowId device row id
     * @param fileName    file name
     * @return pieces by ascending start offset
     */
    List<DeviceLogChunk> findByDeviceRowIdAndFileNameOrderByStartOffsetAsc(Integer deviceRowId, String fileName);

    /**
     * The pieces of the files of a period, file by file.
     *
     * @param deviceRowId device row id
     * @param from        first day, inclusive
     * @param to          last day, inclusive
     * @return pieces ordered by day, file and start offset
     */
    @Query("select c from DeviceLogChunk c where c.deviceRowId = ?1 and c.logDay between ?2 and ?3 "
            + "order by c.logDay asc, c.fileName asc, c.startOffset asc")
    List<DeviceLogChunk> findInPeriod(Integer deviceRowId, LocalDate from, LocalDate to);

    /**
     * Pieces older than the retention, for the cleanup.
     *
     * @param before first day that is kept
     * @return pieces of earlier days
     */
    List<DeviceLogChunk> findByLogDayBefore(LocalDate before);

    /** One file of a device as {@link #summaries(Integer)} lists it. */
    interface FileSummary {
        String getFileName();

        LocalDate getLogDay();

        Long getSize();

        Long getLines();

        LocalDateTime getFirstAt();

        LocalDateTime getLastAt();

        LocalDateTime getSyncedAt();
    }
}
