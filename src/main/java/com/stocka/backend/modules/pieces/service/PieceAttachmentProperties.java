package com.stocka.backend.modules.pieces.service;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits applied to piece attachments. Loaded from {@code stocka.pieces.attachment.*}.
 */
@ConfigurationProperties(prefix = "stocka.pieces.attachment")
public class PieceAttachmentProperties {
    private long maxImageBytes = 26_214_400L; // 25 MB
    private long maxDocumentBytes = 104_857_600L; // 100 MB
    private int maxImagesPerPiece = 50;
    private int maxDocumentsPerPiece = 50;
    private int maxImageDimensionPixels = 16_384; // decompression-bomb guard (issue #14)
    private Set<String> allowedImageMimes = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );
    private long maxAudioBytes = 52_428_800L; // 50 MB
    private int maxAudiosPerPiece = 50;

    /**
     * Long side, in pixels, of the JPEG thumbnail generated for every IMAGE. 512 covers every
     * thumbnail the frontend and the editor paint (56 px rows, 160 px cards, the board stage);
     * the lightbox shows the original.
     */
    private int thumbnailMaxSide = 512;

    /** JPEG quality of the thumbnail, 0..1. */
    private float thumbnailQuality = 0.82f;
    /**
     * Audio formats VLC plays on the Raspberry Pi, as Tika detects them from the magic bytes
     * (WAV is {@code audio/vnd.wave}, Ogg without a parser is {@code application/ogg}).
     */
    private Set<String> allowedAudioMimes = Set.of(
            "audio/mpeg", "audio/vnd.wave", "audio/x-wav", "audio/wav",
            "audio/ogg", "application/ogg", "audio/vorbis",
            "audio/x-flac", "audio/flac", "audio/mp4", "audio/x-m4a", "audio/aac"
    );

    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long v) { this.maxImageBytes = v; }

    public long getMaxDocumentBytes() { return maxDocumentBytes; }
    public void setMaxDocumentBytes(long v) { this.maxDocumentBytes = v; }

    public int getMaxImagesPerPiece() { return maxImagesPerPiece; }
    public void setMaxImagesPerPiece(int v) { this.maxImagesPerPiece = v; }

    public int getMaxDocumentsPerPiece() { return maxDocumentsPerPiece; }
    public void setMaxDocumentsPerPiece(int v) { this.maxDocumentsPerPiece = v; }

    public int getMaxImageDimensionPixels() { return maxImageDimensionPixels; }
    public void setMaxImageDimensionPixels(int v) { this.maxImageDimensionPixels = v; }

    public Set<String> getAllowedImageMimes() { return allowedImageMimes; }
    public void setAllowedImageMimes(Set<String> v) { this.allowedImageMimes = v; }

    public long getMaxAudioBytes() { return maxAudioBytes; }
    public void setMaxAudioBytes(long v) { this.maxAudioBytes = v; }

    public int getMaxAudiosPerPiece() { return maxAudiosPerPiece; }
    public void setMaxAudiosPerPiece(int v) { this.maxAudiosPerPiece = v; }

    public Set<String> getAllowedAudioMimes() { return allowedAudioMimes; }
    public void setAllowedAudioMimes(Set<String> v) { this.allowedAudioMimes = v; }
    public int getThumbnailMaxSide() { return thumbnailMaxSide; }
    public void setThumbnailMaxSide(int v) { this.thumbnailMaxSide = v; }
    public float getThumbnailQuality() { return thumbnailQuality; }
    public void setThumbnailQuality(float v) { this.thumbnailQuality = v; }
}
