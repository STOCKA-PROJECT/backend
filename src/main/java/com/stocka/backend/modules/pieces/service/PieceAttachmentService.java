package com.stocka.backend.modules.pieces.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
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
import com.stocka.backend.modules.pieces.dto.ThumbnailBackfillReport;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.storage.PresignedDownload;
import com.stocka.backend.modules.storage.R2Keys;
import com.stocka.backend.modules.storage.R2Properties;
import com.stocka.backend.modules.storage.R2Service;
import com.stocka.backend.modules.storage.R2UnavailableException;
import com.stocka.backend.modules.storage.UploadedObject;
import com.stocka.backend.modules.users.entity.User;

/**
 * Upload, list, presign-download, stream and delete piece attachments. Validates per-{@code kind}
 * MIME and size limits, talks to R2 through {@link R2Service}, and records every change in piece
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
    private static final String MIME_JPEG = "image/jpeg";
    /** Upper bound of the {@code batch} of {@link #backfillThumbnails(int)}: one call stays short. */
    public static final int MAX_BACKFILL_BATCH = 500;
    private static final Tika TIKA = new Tika();

    private final PieceAttachmentRepository attachmentRepository;
    private final PieceRepository pieceRepository;
    private final PieceService pieceService;
    private final PieceHistoryService historyService;
    private final R2Service r2Service;
    private final R2Properties r2Properties;
    private final PieceAttachmentProperties limits;
    private final OrganizationQuotaProperties quotas;
    private final AttachmentThumbnailService thumbnails;

    public PieceAttachmentService(
            PieceAttachmentRepository attachmentRepository,
            PieceRepository pieceRepository,
            PieceService pieceService,
            PieceHistoryService historyService,
            R2Service r2Service,
            R2Properties r2Properties,
            PieceAttachmentProperties limits,
            OrganizationQuotaProperties quotas,
            AttachmentThumbnailService thumbnails
    ) {
        this.attachmentRepository = attachmentRepository;
        this.pieceRepository = pieceRepository;
        this.pieceService = pieceService;
        this.historyService = historyService;
        this.r2Service = r2Service;
        this.r2Properties = r2Properties;
        this.limits = limits;
        this.quotas = quotas;
        this.thumbnails = thumbnails;
    }

    @Transactional
    public PieceAttachment upload(Integer orgId, Integer pieceId, PieceAttachmentKind kind, MultipartFile file) {
        return upload(orgId, pieceId, kind, file, null);
    }

    /**
     * Uploads an attachment. {@code durationMs} is the length of an {@link PieceAttachmentKind#AUDIO}
     * file as measured by the client (the backend does not decode audio); it is ignored for the
     * other kinds and may be {@code null}.
     *
     * @param orgId      organization the piece belongs to
     * @param pieceId    piece receiving the attachment
     * @param kind       section of the piece the file goes to
     * @param file       uploaded bytes; the MIME type is detected from them, not from the client
     * @param durationMs audio length in milliseconds, or {@code null} when unknown
     * @return the persisted attachment
     * @throws ApiException on a missing kind or file, a MIME, size or count limit, a negative
     *                      duration, or an exhausted organization quota
     */
    @Transactional
    public PieceAttachment upload(Integer orgId, Integer pieceId, PieceAttachmentKind kind, MultipartFile file,
                                  Long durationMs) {
        if (kind == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.UPLOAD_INVALID_KIND);
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_REQUIRED, Map.of("field", "file"));
        }
        if (durationMs != null && durationMs < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.UPLOAD_INVALID_DURATION,
                    Map.of("durationMs", durationMs));
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

        // Generated before anything reaches R2: a failure here (undecodable image, bug in a
        // decoder) is logged and the upload goes on without a thumbnail.
        Optional<Thumbnail> thumbnail = kind == PieceAttachmentKind.IMAGE
                ? generateThumbnail(bytes, safeName)
                : Optional.empty();

        UploadedObject stored = r2Service.upload(key, new ByteArrayInputStream(bytes), size, detectedMime);

        PieceAttachment attachment = new PieceAttachment()
                .setPiece(piece)
                .setKind(kind)
                .setR2Key(stored.key())
                .setOriginalFilename(safeName)
                .setMimeType(detectedMime)
                .setSizeBytes(stored.sizeBytes())
                .setDurationMs(kind == PieceAttachmentKind.AUDIO ? durationMs : null)
                .setUploadedBy(currentUser());
        thumbnail.ifPresent(t -> storeThumbnail(attachment, t));
        PieceAttachment saved = attachmentRepository.save(attachment);
        historyService.recordAttachmentAdded(piece, currentUser(), safeName);
        if (kind == PieceAttachmentKind.IMAGE && piece.getCoverAttachment() == null) {
            piece.setCoverAttachment(saved);
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

    /**
     * Opens an attachment's bytes so the controller can stream them to the client. Used by the
     * timeline editor, whose origin is not allowed to read the R2 bucket directly.
     *
     * @param orgId        organization the piece belongs to
     * @param pieceId      piece the attachment belongs to
     * @param attachmentId the attachment
     * @return the attachment metadata and an open stream of its content
     * @throws org.springframework.web.server.ResponseStatusException 404 when the attachment is not
     *                                                                 in the piece
     * @throws com.stocka.backend.modules.storage.R2ObjectNotFoundException when the bytes are gone
     */
    public AttachmentContent openContent(Integer orgId, Integer pieceId, Integer attachmentId) {
        return openContent(findInPiece(orgId, pieceId, attachmentId));
    }

    /**
     * Opens the original bytes of an attachment already resolved with
     * {@link #requireInPiece(Integer, Integer, Integer)}.
     *
     * @param attachment the attachment
     * @return the attachment and an open stream of its original
     * @throws com.stocka.backend.modules.storage.R2ObjectNotFoundException when the bytes are gone
     */
    public AttachmentContent openContent(PieceAttachment attachment) {
        return new AttachmentContent(attachment, r2Service.open(attachment.getR2Key()));
    }

    /**
     * Opens the JPEG thumbnail of an IMAGE attachment. The caller checks
     * {@link PieceAttachment#hasThumbnail()} first and serves the original otherwise.
     *
     * @param attachment an attachment with a thumbnail
     * @return the attachment and an open stream of its thumbnail ({@code image/jpeg},
     *         {@link PieceAttachment#getThumbSizeBytes()} bytes)
     * @throws IllegalStateException when the attachment has no thumbnail
     * @throws com.stocka.backend.modules.storage.R2ObjectNotFoundException when the bytes are gone
     */
    public AttachmentContent openThumbnail(PieceAttachment attachment) {
        if (!attachment.hasThumbnail()) {
            throw new IllegalStateException("Attachment " + attachment.getId() + " has no thumbnail");
        }
        return new AttachmentContent(attachment, r2Service.open(attachment.getThumbKey()));
    }

    /**
     * Resolves an attachment of a piece of an organization, for the controllers that need its
     * metadata (ETag, size, MIME) before deciding whether to open any bytes at all.
     *
     * @param orgId        organization the piece belongs to
     * @param pieceId      piece the attachment belongs to
     * @param attachmentId the attachment
     * @return the attachment
     * @throws ResponseStatusException 404 when the piece is not in the organization or the
     *                                 attachment is not in the piece
     */
    public PieceAttachment requireInPiece(Integer orgId, Integer pieceId, Integer attachmentId) {
        return findInPiece(orgId, pieceId, attachmentId);
    }

    /**
     * Generates the thumbnail of up to {@code batchSize} IMAGE attachments that have none (images
     * uploaded before V32, or whose thumbnail failed earlier). Each attachment is read from R2,
     * decoded, stored and saved on its own, so a failure only skips that one; the report names the
     * failures, which the next batch retries.
     *
     * @param batchSize how many attachments to process, clamped to {@code 1..}{@link #MAX_BACKFILL_BATCH}
     * @return what happened and how many are still pending
     */
    public ThumbnailBackfillReport backfillThumbnails(int batchSize) {
        int size = Math.max(1, Math.min(batchSize, MAX_BACKFILL_BATCH));
        List<PieceAttachment> pending = attachmentRepository.findByKindWithoutThumbnail(
                PieceAttachmentKind.IMAGE, PageRequest.of(0, size));
        int generated = 0;
        List<Integer> failed = new ArrayList<>();
        for (PieceAttachment attachment : pending) {
            if (backfillOne(attachment)) {
                generated++;
            } else {
                failed.add(attachment.getId());
            }
        }
        long remaining = attachmentRepository.countByKindWithoutThumbnail(PieceAttachmentKind.IMAGE);
        log.info("thumbnail_backfill processed={} generated={} failed={} remaining={}",
                pending.size(), generated, failed.size(), remaining);
        return new ThumbnailBackfillReport(pending.size(), generated, List.copyOf(failed), remaining);
    }

    private boolean backfillOne(PieceAttachment attachment) {
        byte[] bytes;
        try (InputStream in = r2Service.open(attachment.getR2Key())) {
            bytes = in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            log.warn("thumbnail_backfill_read_failed attachmentId={} key={} reason={}",
                    attachment.getId(), attachment.getR2Key(), e.getMessage());
            return false;
        }
        Optional<Thumbnail> thumbnail = generateThumbnail(bytes, attachment.getOriginalFilename());
        if (thumbnail.isEmpty() || !storeThumbnail(attachment, thumbnail.get())) {
            return false;
        }
        attachmentRepository.save(attachment);
        return true;
    }

    /** Decodes and scales {@code bytes}; empty (and a warning) when the image cannot be handled. */
    private Optional<Thumbnail> generateThumbnail(byte[] bytes, String filename) {
        try {
            Optional<Thumbnail> thumbnail = thumbnails.generate(bytes);
            if (thumbnail.isEmpty()) {
                log.warn("thumbnail_unsupported_format filename={}", filename);
            }
            return thumbnail;
        } catch (IOException | RuntimeException e) {
            log.warn("thumbnail_generation_failed filename={} reason={}", filename, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Uploads the thumbnail next to the original and records it on the (unsaved) attachment. The
     * displayed size of the original is recorded even when the upload fails.
     *
     * @return whether the thumbnail is now in R2
     */
    private boolean storeThumbnail(PieceAttachment attachment, Thumbnail thumbnail) {
        attachment.setWidth(thumbnail.sourceWidth()).setHeight(thumbnail.sourceHeight());
        String thumbKey = R2Keys.thumbnailKeyFor(attachment.getR2Key());
        byte[] bytes = thumbnail.bytes();
        try {
            UploadedObject stored = r2Service.upload(thumbKey, new ByteArrayInputStream(bytes), bytes.length, MIME_JPEG);
            attachment.setThumbKey(stored.key())
                    .setThumbWidth(thumbnail.width())
                    .setThumbHeight(thumbnail.height())
                    .setThumbSizeBytes(stored.sizeBytes());
            return true;
        } catch (R2UnavailableException e) {
            log.warn("thumbnail_upload_failed key={} reason={}", thumbKey, e.getMessage());
            return false;
        }
    }

    @Transactional
    public void softDelete(Integer orgId, Integer pieceId, Integer attachmentId) {
        PieceAttachment attachment = findInPiece(orgId, pieceId, attachmentId);
        Piece piece = attachment.getPiece();
        attachment.setDeletedAt(LocalDateTime.now());
        attachmentRepository.save(attachment);
        if (piece.getCoverAttachment() != null
                && piece.getCoverAttachment().getId().equals(attachment.getId())) {
            piece.setCoverAttachment(null);
            pieceRepository.save(piece);
        }
        try {
            r2Service.delete(attachment.getR2Key());
        } catch (R2UnavailableException e) {
            log.warn("r2_delete_failed key={} reason={}", attachment.getR2Key(), e.getMessage());
        }
        if (attachment.hasThumbnail()) {
            try {
                r2Service.delete(attachment.getThumbKey());
            } catch (R2UnavailableException e) {
                log.warn("r2_delete_failed key={} reason={}", attachment.getThumbKey(), e.getMessage());
            }
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
        } else if (kind == PieceAttachmentKind.AUDIO) {
            if (!limits.getAllowedAudioMimes().contains(mime)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCodes.UPLOAD_INVALID_KIND,
                        Map.of("mime", mime));
            }
            if (size > limits.getMaxAudioBytes()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCodes.UPLOAD_TOO_LARGE,
                        Map.of("max", limits.getMaxAudioBytes()));
            }
            long current = attachmentRepository.countByPieceAndKind(piece, PieceAttachmentKind.AUDIO);
            if (current >= limits.getMaxAudiosPerPiece()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCodes.UPLOAD_TOO_LARGE,
                        Map.of("max", limits.getMaxAudiosPerPiece()));
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
        return dispositionFor("attachment", originalFilename);
    }

    /**
     * Same as {@link #attachmentDispositionFor(String)} with {@code inline} disposition, for the
     * content endpoint (the browser plays or shows the file instead of saving it).
     *
     * @param originalFilename the name to announce; {@code null} becomes {@code "file"}
     * @return the header value
     */
    public static String inlineDispositionFor(String originalFilename) {
        return dispositionFor("inline", originalFilename);
    }

    private static String dispositionFor(String type, String originalFilename) {
        String filename = originalFilename == null ? "file" : originalFilename;
        String safe = filename.replaceAll("[\\r\\n\"\\\\]", "_");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return type + "; filename=\"" + safe + "\"; filename*=UTF-8''" + encoded;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return null;
        }
        return user;
    }
}
