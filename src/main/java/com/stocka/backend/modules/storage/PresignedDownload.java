package com.stocka.backend.modules.storage;

import java.time.Instant;

/**
 * Time-limited download URL for an object stored in R2 (or for the local-fallback equivalent).
 *
 * @param url       absolute URL the client can {@code GET} to download the object
 * @param expiresAt instant after which the URL stops working
 */
public record PresignedDownload(String url, Instant expiresAt) {
}
