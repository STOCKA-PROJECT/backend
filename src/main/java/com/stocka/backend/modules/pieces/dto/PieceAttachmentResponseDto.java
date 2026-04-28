package com.stocka.backend.modules.pieces.dto;

import java.util.Date;

import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;

/** Metadata about a piece attachment exposed to the client. The {@code r2Key} is intentionally omitted. */
public record PieceAttachmentResponseDto(
        Integer id,
        PieceAttachmentKind kind,
        String originalFilename,
        String mimeType,
        long sizeBytes,
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
                attachment.getUploadedBy() == null ? null : attachment.getUploadedBy().getId(),
                attachment.getCreatedAt()
        );
    }
}
