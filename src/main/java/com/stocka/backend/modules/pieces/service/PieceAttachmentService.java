package com.stocka.backend.modules.pieces.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.service.OrganizationQuotaProperties;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.sync.support.SyncStamper;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.storage.PresignedDownload;
import com.stocka.backend.modules.storage.R2Properties;
import com.stocka.backend.modules.storage.R2Service;
import com.stocka.backend.modules.storage.R2UnavailableException;
import com.stocka.backend.modules.storage.UploadedObject;
import com.stocka.backend.modules.users.entity.User;

/**
 * Upload, list, presign-download and delete piece attachments. Validates per-{@code kind} MIME
 * and size limits, talks to R2 through {@link R2Service}, and records every change in piece
 * history.
 *
 * <p>Upload validation pipeline (issue #14):
 * <ol>
 *   <li>Detect the real MIME with {@link Tika#detect(InputStream)} (magic bytes), ignoring the
 *       client-declared {@code Content-Type}.</li>
 *   <li>Reject {@code image/svg+xml} explicitly — SVG can embed JavaScript (stored XSS).</li>
 *   <li>Apply per-kind MIME/size/count limits using the detected MIME.</li>
 *   <li>For images, read the header dimensions without decoding the full pixel data
 *       (decompression-bomb protection) and reject anything bigger than
 *       {@link PieceAttachmentProperties#getMaxImageDimensionPixels()} on either axis.</li>
 *   <li>Push the bytes to R2 forcing the detected MIME as {@code Content-Type} on the stored
 *       object.</li>
 * </ol>
 *
 * <p>This service does <em>not</em> run an antivirus scan on uploaded bytes. Integrating ClamAV
 * or the VirusTotal API was considered and explicitly deferred.
 */
@Service
public class PieceAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(PieceAttachmentService.class);

    private static final String MIME_OCTET_STREAM = "application/octet-stream";
    private static final String MIME_SVG = "image/svg+xml";
    private static final String MIME_WEBP = "image/webp";
    private static final Tika TIKA = new Tika();

    private final PieceAttachmentRepository attachmentRepository;
    private final PieceRepository pieceRepository;
    private final PieceService pieceService;
    private final PieceHistoryService historyService;
    private final R2Service r2Service;
    private final R2Properties r2Properties;
    private final PieceAttachmentProperties limits;
    private final OrganizationQuotaProperties quotas;
    private final SyncStamper syncStamper;

    public PieceAttachmentService(
            PieceAttachmentRepository attachmentRepository,
            PieceRepository pieceRepository,
            PieceService pieceService,
            PieceHistoryService historyService,
            R2Service r2Service,
            R2Properties r2Properties,
            PieceAttachmentProperties limits,
            OrganizationQuotaProperties quotas,
            SyncStamper syncStamper
    ) {
        this.attachmentRepository = attachmentRepository;
        this.pieceRepository = pieceRepository;
        this.pieceService = pieceService;
        this.historyService = historyService;
        this.r2Service = r2Service;
        this.r2Properties = r2Properties;
        this.limits = limits;
        this.quotas = quotas;
        this.syncStamper = syncStamper;
    }

    @Transactional
    public PieceAttachment upload(Integer orgId, Integer pieceId, PieceAttachmentKind kind, MultipartFile file) {
        if (kind == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.UPLOAD_INVALID_KIND);
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_REQUIRED, Map.of("field", "file"));
        }
        Piece piece = pieceService.findInOrg(orgId, pieceId);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo leer el archivo subido: " + e.getMessage());
        }
        long size = bytes.length;

        // Trust magic bytes, not the client-declared Content-Type (issue #14).
        String detectedMime = detectMime(bytes);

        if (MIME_SVG.equalsIgnoreCase(detectedMime)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.UPLOAD_INVALID_KIND,
                    Map.of("mime", detectedMime));
        }

        validateForKind(piece, kind, detectedMime, size);

        if (kind == PieceAttachmentKind.IMAGE) {
            validateImageDimensions(bytes, detectedMime);
        }

        String safeName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String key = r2Service.buildPieceKey(piece.getOrganization().getId(), piece.getId(), safeName);

        UploadedObject stored = r2Service.upload(key, new ByteArrayInputStream(bytes), size, detectedMime);

        PieceAttachment attachment = new PieceAttachment()
                .setPiece(piece)
                .setKind(kind)
                .setR2Key(stored.key())
                .setOriginalFilename(safeName)
                .setMimeType(detectedMime)
                .setSizeBytes(stored.sizeBytes())
                .setUploadedBy(currentUser());
        syncStamper.stamp(attachment);
        PieceAttachment saved = attachmentRepository.save(attachment);
        historyService.recordAttachmentAdded(piece, currentUser(), safeName);
        if (kind == PieceAttachmentKind.IMAGE && piece.getCoverAttachment() == null) {
            piece.setCoverAttachment(saved);
            syncStamper.stamp(piece);
            pieceRepository.save(piece);
        }
        return saved;
    }

    public List<PieceAttachment> list(Integer orgId, Integer pieceId, PieceAttachmentKind kindFilter) {
        Piece piece = pieceService.findInOrg(orgId, pieceId);
        if (kindFilter == null) {
            return attachmentRepository.findByPiece(piece);
        }
        return attachmentRepository.findByPieceAndKind(piece, kindFilter);
    }

    public PresignedDownload presign(Integer orgId, Integer pieceId, Integer attachmentId) {
        PieceAttachment attachment = findInPiece(orgId, pieceId, attachmentId);
        // Documents are downloaded as a file, images are previewed inline (issue #14).
        String disposition = attachment.getKind() == PieceAttachmentKind.DOCUMENT
                ? attachmentDispositionFor(attachment.getOriginalFilename())
                : null;
        return r2Service.presign(
                attachment.getR2Key(),
                Duration.ofMinutes(r2Properties.getPresignedTtlMinutes()),
                disposition);
    }

    @Transactional
    public void softDelete(Integer orgId, Integer pieceId, Integer attachmentId) {
        PieceAttachment attachment = findInPiece(orgId, pieceId, attachmentId);
        Piece piece = attachment.getPiece();
        attachment.setDeletedAt(LocalDateTime.now());
        syncStamper.stamp(attachment);
        attachmentRepository.save(attachment);
        if (piece.getCoverAttachment() != null
                && piece.getCoverAttachment().getId().equals(attachment.getId())) {
            piece.setCoverAttachment(null);
            syncStamper.stamp(piece);
            pieceRepository.save(piece);
        }
        try {
            r2Service.delete(attachment.getR2Key());
        } catch (R2UnavailableException e) {
            log.warn("r2_delete_failed key={} reason={}", attachment.getR2Key(), e.getMessage());
        }
        historyService.recordAttachmentRemoved(piece, currentUser(), attachment.getOriginalFilename());
    }

    private PieceAttachment findInPiece(Integer orgId, Integer pieceId, Integer attachmentId) {
        Piece piece = pieceService.findInOrg(orgId, pieceId);
        PieceAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Adjunto no encontrado"));
        if (!attachment.getPiece().getId().equals(piece.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Adjunto no encontrado");
        }
        return attachment;
    }

    private void validateForKind(Piece piece, PieceAttachmentKind kind, String mime, long size) {
        long currentBytes = attachmentRepository.sumSizeBytesByOrganization(piece.getOrganization());
        long maxBytes = quotas.getMaxBytesPerOrg();
        if (currentBytes + size > maxBytes) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.ORGANIZATIONS_QUOTA_EXCEEDED,
                    Map.of(
                            "limit", "max_bytes_per_org",
                            "max", maxBytes,
                            "current", currentBytes,
                            "requested", size));
        }
        if (kind == PieceAttachmentKind.IMAGE) {
            if (!limits.getAllowedImageMimes().contains(mime)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCodes.UPLOAD_INVALID_KIND,
                        Map.of("mime", mime));
            }
            if (size > limits.getMaxImageBytes()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCodes.UPLOAD_TOO_LARGE,
                        Map.of("max", limits.getMaxImageBytes()));
            }
            long current = attachmentRepository.countByPieceAndKind(piece, PieceAttachmentKind.IMAGE);
            if (current >= limits.getMaxImagesPerPiece()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCodes.UPLOAD_TOO_LARGE,
                        Map.of("max", limits.getMaxImagesPerPiece()));
            }
        } else {
            if (size > limits.getMaxDocumentBytes()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCodes.UPLOAD_TOO_LARGE,
                        Map.of("max", limits.getMaxDocumentBytes()));
            }
            long current = attachmentRepository.countByPieceAndKind(piece, PieceAttachmentKind.DOCUMENT);
            if (current >= limits.getMaxDocumentsPerPiece()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCodes.UPLOAD_TOO_LARGE,
                        Map.of("max", limits.getMaxDocumentsPerPiece()));
            }
        }
    }

    private void validateImageDimensions(byte[] bytes, String mime) {
        int[] dims = readImageHeaderDimensions(bytes);
        if (dims == null) {
            // No ImageIO reader for this format. WebP is the common case in the allowed
            // MIME list — accept it (size limits still apply) and reject anything else
            // as malformed, since we already vetted the MIME against the allow list.
            if (MIME_WEBP.equalsIgnoreCase(mime)) {
                return;
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.UPLOAD_INVALID_KIND,
                    Map.of("mime", mime));
        }
        int max = limits.getMaxImageDimensionPixels();
        if (dims[0] > max || dims[1] > max) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.UPLOAD_IMAGE_DIMENSIONS_TOO_LARGE,
                    Map.of("max", max, "width", dims[0], "height", dims[1]));
        }
    }

    /**
     * Reads {@code [width, height]} from the image header without decoding pixels — the key
     * detail that keeps this safe against decompression bombs. Returns {@code null} when no
     * registered {@link ImageReader} understands the format.
     */
    private int[] readImageHeaderDimensions(byte[] bytes) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true);
                return new int[] { reader.getWidth(0), reader.getHeight(0) };
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private String detectMime(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            String detected = TIKA.detect(in);
            return detected == null ? MIME_OCTET_STREAM : detected.toLowerCase();
        } catch (IOException e) {
            return MIME_OCTET_STREAM;
        }
    }

    /**
     * Builds a {@code Content-Disposition: attachment} header value with the original filename
     * in both ASCII (escaped) and UTF-8 ({@code filename*}) forms so non-Latin filenames survive
     * the round trip through R2.
     */
    private static String attachmentDispositionFor(String originalFilename) {
        String filename = originalFilename == null ? "file" : originalFilename;
        String safe = filename.replaceAll("[\\r\\n\"\\\\]", "_");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + safe + "\"; filename*=UTF-8''" + encoded;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return null;
        }
        return user;
    }
}
