package com.stocka.backend.modules.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Local-disk fallback for {@link R2Service}. Used in development and tests when no real
 * Cloudflare R2 bucket is available. Files live under {@link R2Properties#getLocalDir()}; the
 * presigned URL points to {@code LocalR2DownloadController}, which streams them back to the
 * caller.
 *
 * <p>Active by default: only when {@code stocka.r2.use-local=false} the real
 * {@code R2StorageServiceImpl} is wired instead.
 */
@Service
@ConditionalOnProperty(name = "stocka.r2.use-local", havingValue = "true", matchIfMissing = true)
public class LocalR2StorageService implements R2Service {
    private static final Logger log = LoggerFactory.getLogger(LocalR2StorageService.class);

    /**
     * S3-compatible query parameter name. Mirrors AWS' own presigned-URL contract so the local
     * download controller and the real R2 path stay symmetrical.
     */
    static final String RESPONSE_CONTENT_DISPOSITION_PARAM = "response-content-disposition";

    private final R2Properties properties;

    public LocalR2StorageService(R2Properties properties) {
        this.properties = properties;
    }

    @Override
    public UploadedObject upload(String key, InputStream stream, long sizeBytes, String mimeType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            long actual = Files.size(target);
            log.info("local_r2_upload key={} size={}", key, actual);
            return new UploadedObject(key, actual, mimeType);
        } catch (IOException e) {
            throw new R2UnavailableException("No se pudo escribir el archivo local: " + key, e);
        }
    }

    @Override
    public PresignedDownload presign(String key, Duration ttl, String contentDisposition) {
        String encoded = Arrays.stream(key.split("/"))
                .map(seg -> URLEncoder.encode(seg, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
        StringBuilder url = new StringBuilder("/dev/r2/").append(encoded);
        if (contentDisposition != null && !contentDisposition.isBlank()) {
            url.append('?').append(RESPONSE_CONTENT_DISPOSITION_PARAM).append('=')
                    .append(URLEncoder.encode(contentDisposition, StandardCharsets.UTF_8));
        }
        Instant expiresAt = Instant.now().plus(ttl);
        return new PresignedDownload(url.toString(), expiresAt);
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new R2UnavailableException("No se pudo eliminar el archivo local: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public String buildPieceKey(int orgId, int pieceId, String originalFilename) {
        return R2Keys.buildPieceKey(orgId, pieceId, originalFilename);
    }

    Path resolve(String key) {
        Path base = Path.of(properties.getLocalDir()).toAbsolutePath().normalize();
        Path candidate = base.resolve(key).normalize();
        if (!candidate.startsWith(base)) {
            throw new R2UnavailableException("Ruta fuera del directorio local permitido: " + key);
        }
        return candidate;
    }
}
