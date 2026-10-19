package com.stocka.backend.modules.timelines.show;

import com.stocka.backend.modules.pieces.entity.PieceAttachment;

/**
 * An AUDIO attachment of a piece, as the show builder needs it to resolve asset names.
 *
 * @param id               attachment id (the front of the asset name)
 * @param originalFilename original file name
 * @param mimeType         MIME type detected at upload
 * @param sizeBytes        size in bytes
 * @param durationMs       length measured by the client, or {@code null} when unknown
 */
public record ShowAudioFile(int id, String originalFilename, String mimeType, long sizeBytes, Long durationMs) {

    /**
     * @param attachment a persisted AUDIO attachment
     * @return its show view
     */
    public static ShowAudioFile from(PieceAttachment attachment) {
        return new ShowAudioFile(attachment.getId(), attachment.getOriginalFilename(), attachment.getMimeType(),
                attachment.getSizeBytes(), attachment.getDurationMs());
    }

    /** @return the asset name the show uses for this file ({@code <id>-<file>}) */
    public String assetName() {
        return AudioAssets.assetNameFor(id, originalFilename);
    }
}
