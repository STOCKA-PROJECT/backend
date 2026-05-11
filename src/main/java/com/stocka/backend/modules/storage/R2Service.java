package com.stocka.backend.modules.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * Abstraction over the Cloudflare R2 (S3-compatible) object store used by Stocka.
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@code R2StorageServiceImpl} — talks to R2 via the AWS SDK v2.</li>
 *   <li>{@code LocalR2StorageService} — writes to the local filesystem; used in dev.</li>
 * </ul>
 * The active implementation is selected via the {@code stocka.r2.use-local} property.
 */
public interface R2Service {

    /**
     * Stores {@code stream} as a new object under {@code key}. Caller is responsible for closing
     * the stream after this method returns.
     *
     * @param key       object key inside the bucket
     * @param stream    binary content to store
     * @param sizeBytes exact length of {@code stream} in bytes
     * @param mimeType  Content-Type to associate with the object
     * @return metadata describing the persisted object
     * @throws R2UnavailableException if the underlying store is unreachable
     */
    UploadedObject upload(String key, InputStream stream, long sizeBytes, String mimeType);

    /**
     * Generates a temporary URL the client can {@code GET} to download the object.
     *
     * @param key object key
     * @param ttl how long the URL stays valid
     * @return the presigned URL together with its expiration timestamp
     * @throws R2UnavailableException if the underlying store cannot generate the URL
     */
    default PresignedDownload presign(String key, Duration ttl) {
        return presign(key, ttl, null);
    }

    /**
     * Same as {@link #presign(String, Duration)} but overrides the {@code Content-Disposition}
     * header returned when the object is fetched through the presigned URL. Used to force
     * {@code attachment} disposition for document downloads (issue #14).
     *
     * @param key                object key
     * @param ttl                how long the URL stays valid
     * @param contentDisposition value for the {@code Content-Disposition} header, or
     *                           {@code null} to leave the stored value untouched
     * @return the presigned URL together with its expiration timestamp
     * @throws R2UnavailableException if the underlying store cannot generate the URL
     */
    PresignedDownload presign(String key, Duration ttl, String contentDisposition);

    /**
     * Removes the object from R2. Best-effort: callers may swallow exceptions and log.
     *
     * @param key object key to delete
     * @throws R2UnavailableException if the deletion request fails for transport reasons
     */
    void delete(String key);

    /**
     * @return {@code true} if an object with {@code key} exists in the bucket
     */
    boolean exists(String key);

    /**
     * Builds a deterministic, collision-resistant key for a piece attachment under
     * {@code org/{orgId}/piece/{pieceId}/{uuid}-{safeFilename}}.
     *
     * @param orgId            organization id (used for tenant isolation in the key path)
     * @param pieceId          piece id the attachment belongs to
     * @param originalFilename original file name from the upload (sanitized internally)
     * @return the object key to pass to {@link #upload}
     */
    String buildPieceKey(int orgId, int pieceId, String originalFilename);
}
