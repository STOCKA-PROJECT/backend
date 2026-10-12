package com.stocka.backend.modules.pieces.dto;

import java.util.Date;

import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;

/**
 * Metadata about a piece attachment exposed to the client. The {@code r2Key} is intentionally
 * omitted. {@code durationMs} is only set for {@link PieceAttachmentKind#AUDIO} attachments whose
 * upload reported it.
 */
public record PieceAttachmentResponseDto(
        Integer id,
        PieceAttachmentKind kind,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        Long durationMs,
        Integer uploadedByUserId,
        Date createdAt
) {
    public static PieceAttachmentResponseDto from(PieceAttachment attachment) {
        return new PieceAttachmentResponseDto(
                attachment.getId(),
                attachment.getKind(),
                attachment.getOriginalFilename(),
                attachment.getMimeType(),
                attachment.getSizeBytes(),
                attachment.getDurationMs(),
                attachment.getUploadedBy() == null ? null : attachment.getUploadedBy().getId(),
                attachment.getCreatedAt()
        );
    }
}
