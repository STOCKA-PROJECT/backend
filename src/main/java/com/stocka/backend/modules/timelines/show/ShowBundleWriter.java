package com.stocka.backend.modules.timelines.show;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.storage.R2Service;

/**
 * Writes a {@code .belen.zip} ({@code stocka-bundle} v1, see
 * {@code rpi4-system/contract/bundle/bundle.schema.json}): {@code show.json} deflated, every asset
 * stored as is (audio and video are already compressed) and {@code manifest.json} last, with the
 * SHA-256 and size of every file so the Raspberry Pi can verify the bundle offline. Zip readers
 * locate entries by name, so writing the manifest last costs nothing and lets the assets be read
 * from storage once.
 */
@Component
public class ShowBundleWriter {

    /** Manifest format id. */
    public static final String BUNDLE_FORMAT = "stocka-bundle";
    /** Manifest format version. */
    public static final int BUNDLE_VERSION = 1;
    /** Who built the bundle, as the manifest states it. */
    public static final String CREATED_BY = "backend";

    private final R2Service r2;

    public ShowBundleWriter(R2Service r2) {
        this.r2 = r2;
    }

    /**
     * {@code <slug>-<hash8>.belen.zip}, with the slug rule of {@code tools/bundle_tool.py}.
     *
     * @param pkg a hashed package
     * @return the canonical file name
     */
    public static String fileName(ShowPackage pkg) {
        String hash = pkg.meta().hash();
        return slugify(pkg.meta().name()) + "-" + hash.substring(7, 15) + ".belen.zip";
    }

    /**
     * Lower case, runs of anything but {@code [a-z0-9]} collapsed to {@code -}, trimmed; {@code show}
     * when nothing is left.
     *
     * @param name show name
     * @return the slug
     */
    public static String slugify(String name) {
        String slug = (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-+", "").replaceAll("-+$", "");
        return slug.isEmpty() ? "show" : slug;
    }

    /**
     * Streams the bundle. The caller must have checked that every asset exists; a missing object here
     * surfaces as an {@link IOException} after the response has started.
     *
     * @param bundle the bundle to write
     * @param out    target stream (not closed)
     * @param now    creation instant for the manifest
     * @throws IOException when storage or the target stream fail
     */
    public void write(ShowBundle bundle, OutputStream out, Instant now) throws IOException {
        List<Map<String, Object>> files = new ArrayList<>();
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        byte[] show = (ShowCanonicalJson.pretty(bundle.pkg()) + "\n").getBytes(StandardCharsets.UTF_8);
        writeDeflated(zip, "show.json", show);
        files.add(fileEntry("show.json", ShowCanonicalJson.sha256Hex(show), show.length, "show"));
        for (ShowBundle.BundleAsset asset : bundle.assets()) {
            byte[] bytes;
            try (InputStream in = r2.open(asset.r2Key())) {
                bytes = in.readAllBytes();
            }
            writeStored(zip, asset.path(), bytes);
            files.add(fileEntry(asset.path(), ShowCanonicalJson.sha256Hex(bytes), bytes.length, asset.kind()));
        }
        byte[] manifest = (ShowCanonicalJson.pretty(manifest(bundle.pkg(), files, now)) + "\n").getBytes(StandardCharsets.UTF_8);
        writeDeflated(zip, "manifest.json", manifest);
        zip.finish();
        zip.flush();
    }

    /**
     * The manifest of a bundle.
     *
     * @param pkg   the show package
     * @param files file entries ({@code path}, {@code sha256}, {@code size}, {@code kind})
     * @param now   creation instant
     * @return the manifest document, in schema order
     */
    public static Map<String, Object> manifest(ShowPackage pkg, List<Map<String, Object>> files, Instant now) {
        Map<String, Object> show = new LinkedHashMap<>();
        show.put("hash", pkg.meta().hash());
        show.put("name", pkg.meta().name());
        show.put("timelineId", pkg.meta().timelineId());
        show.put("board", pkg.board());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", BUNDLE_FORMAT);
        manifest.put("version", BUNDLE_VERSION);
        manifest.put("show", show);
        manifest.put("files", files);
        manifest.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(now.truncatedTo(ChronoUnit.SECONDS)));
        manifest.put("createdBy", CREATED_BY);
        return manifest;
    }

    private static Map<String, Object> fileEntry(String path, String sha256, long size, String kind) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", path);
        entry.put("sha256", sha256);
        entry.put("size", size);
        entry.put("kind", kind);
        return entry;
    }

    private static void writeDeflated(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setMethod(ZipEntry.DEFLATED);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void writeStored(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = new ZipEntry(path);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}
