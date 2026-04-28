package com.stocka.backend.modules.storage;

/**
 * Result of a successful upload to R2 (or to the local fallback).
 *
 * @param key       full R2 object key (relative to the bucket root)
 * @param sizeBytes number of bytes actually written
 * @param mimeType  Content-Type that was stored alongside the object
 */
public record UploadedObject(String key, long sizeBytes, String mimeType) {
}
