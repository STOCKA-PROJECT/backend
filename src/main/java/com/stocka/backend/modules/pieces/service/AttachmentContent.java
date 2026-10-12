package com.stocka.backend.modules.pieces.service;

import java.io.InputStream;

import com.stocka.backend.modules.pieces.entity.PieceAttachment;

/**
 * An attachment together with an open stream of its bytes, as returned by
 * {@link PieceAttachmentService#openContent}. The caller owns the stream and must close it
 * (Spring does so when it is wrapped in an {@code InputStreamResource} response body).
 *
 * @param attachment the metadata row (MIME type, size and original name for the headers)
 * @param stream     the object content, positioned at its first byte
 */
public record AttachmentContent(PieceAttachment attachment, InputStream stream) {
}
