package com.stocka.backend.modules.pieces.entity;

/**
 * Independent attachment sections of a piece. {@link #IMAGE} accepts only image MIME types
 * (jpg/png/webp/gif); {@link #DOCUMENT} accepts any MIME, including images; {@link #AUDIO}
 * accepts the audio formats the belén player (VLC on the Raspberry Pi) reproduces and is what the
 * timeline editor offers to {@code audio.play} clips.
 */
public enum PieceAttachmentKind {
    IMAGE,
    DOCUMENT,
    AUDIO
}
