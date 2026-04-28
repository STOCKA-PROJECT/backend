package com.stocka.backend.modules.pieces.entity;

/**
 * Two independent attachment sections of a piece. {@link #IMAGE} accepts only image MIME types
 * (jpg/png/webp/gif); {@link #DOCUMENT} accepts any MIME, including images.
 */
public enum PieceAttachmentKind {
    IMAGE,
    DOCUMENT
}
