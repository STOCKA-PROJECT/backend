package com.stocka.backend.modules.timelines.show;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audio files in a show: an {@code audio.play} clip names an AUDIO attachment of its piece by asset
 * name ({@code <attachmentId>-<filename>}, see {@code rpi4-system/contract/README.md}) and covers a
 * window of the file: from {@code start} seconds for {@code time} seconds, or until the end of the
 * file when there is no duration. Port of the editor's {@code utils/audioAssets.ts}.
 */
public final class AudioAssets {

    private static final Pattern LEADING_ID = Pattern.compile("^(\\d+)-");

    private AudioAssets() {
    }

    /**
     * Seconds of the file that will actually sound.
     *
     * @param fromSec      second of the file where playback starts
     * @param toSec        second where it ends, or {@code null} when the file length is unknown
     * @param beyondEndSec how much of the clip falls after the file ends (0 when it fits or unknown)
     */
    public record AudioFileWindow(double fromSec, Double toSec, double beyondEndSec) {
    }

    /**
     * {@code m:ss} (or {@code h:mm:ss}) for a length in milliseconds; {@code —} when unknown.
     *
     * @param ms length in milliseconds, may be {@code null}
     * @return the clock text
     */
    public static String formatClock(Long ms) {
        if (ms == null || ms < 0) {
            return "—";
        }
        long total = Math.round(ms / 1000.0);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        String mm = h > 0 ? String.format(Locale.ROOT, "%02d", m) : String.valueOf(m);
        return (h > 0 ? h + ":" : "") + mm + ":" + String.format(Locale.ROOT, "%02d", s);
    }

    /**
     * The original file name made safe for the player's media folder (same rule as the backend keys).
     *
     * @param name original file name, may be {@code null}
     * @return the safe name, {@code file} when nothing is left
     */
    public static String safeAssetFilename(String name) {
        String base = (name == null ? "" : name).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return base.isEmpty() ? "file" : base;
    }

    /**
     * Asset name of an attachment: unique (the id) and readable (the file name).
     *
     * @param attachmentId     attachment id
     * @param originalFilename original file name
     * @return {@code <id>-<safe name>}
     */
    public static String assetNameFor(int attachmentId, String originalFilename) {
        return attachmentId + "-" + safeAssetFilename(originalFilename);
    }

    /**
     * The attachment id encoded at the front of an asset name.
     *
     * @param name asset name, any type
     * @return the id, or {@code null} when the name has none
     */
    public static Integer attachmentIdFromAssetName(Object name) {
        if (!(name instanceof String s)) {
            return null;
        }
        Matcher m = LEADING_ID.matcher(s.trim());
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The attachment an asset name points at, among a piece's audio files.
     *
     * @param files the piece's AUDIO attachments
     * @param name  asset name
     * @return the matching file, or {@code null}
     */
    public static ShowAudioFile findAudioByAssetName(List<ShowAudioFile> files, Object name) {
        Integer id = attachmentIdFromAssetName(name);
        if (id == null || files == null) {
            return null;
        }
        return files.stream().filter(a -> a.id() == id).findFirst().orElse(null);
    }

    /**
     * Window of the file a clip covers, cut at the end of the file when its length is known.
     *
     * @param startSec       second of the file where playback starts
     * @param durationSec    seconds played from {@code startSec}; {@code null} means until the end
     * @param fileDurationMs length of the file, or {@code null} when unknown
     * @return the window
     */
    public static AudioFileWindow audioFileWindow(double startSec, Double durationSec, Long fileDurationMs) {
        Double fileSec = fileDurationMs != null && fileDurationMs > 0 ? fileDurationMs / 1000.0 : null;
        double fromSec = fileSec != null ? Math.min(startSec, fileSec) : startSec;
        Double wantedEnd = durationSec != null ? startSec + durationSec : null;
        if (fileSec == null) {
            return new AudioFileWindow(fromSec, wantedEnd, 0);
        }
        double toSec = wantedEnd != null ? Math.min(wantedEnd, fileSec) : fileSec;
        double beyondEndSec = wantedEnd != null ? Math.max(0, wantedEnd - fileSec) : 0;
        return new AudioFileWindow(fromSec, toSec, beyondEndSec);
    }
}
