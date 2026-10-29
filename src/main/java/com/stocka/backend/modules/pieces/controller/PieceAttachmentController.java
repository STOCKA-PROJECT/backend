package com.stocka.backend.modules.pieces.controller;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.pieces.dto.PieceAttachmentResponseDto;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.service.AttachmentContent;
import com.stocka.backend.modules.pieces.service.PieceAttachmentService;
import com.stocka.backend.modules.storage.PresignedDownload;

@RestController
@RequestMapping("/organizations/{orgSlug}/pieces/{pieceId}/attachments")
public class PieceAttachmentController {
    /** Originals and thumbnails never change for a given id: cache them for a year, privately. */
    static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();
    /**
     * What {@code /thumbnail} answers while an image has no thumbnail yet: short enough for the
     * client to come back once the backfill has generated it.
     */
    static final CacheControl FALLBACK = CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate();

    private final PieceAttachmentService attachmentService;
    private final OrganizationResolver orgResolver;

    public PieceAttachmentController(PieceAttachmentService attachmentService, OrganizationResolver orgResolver) {
        this.attachmentService = attachmentService;
        this.orgResolver = orgResolver;
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")
    public ResponseEntity<PieceAttachmentResponseDto> upload(
            @PathVariable String orgSlug,
            @PathVariable Integer pieceId,
            @RequestParam("kind") PieceAttachmentKind kind,
            @RequestParam(value = "durationMs", required = false) Long durationMs,
            @RequestPart("file") MultipartFile file
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        PieceAttachment attachment = attachmentService.upload(orgId, pieceId, kind, file, durationMs);
        return ResponseEntity.status(HttpStatus.CREATED).body(PieceAttachmentResponseDto.from(attachment));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<List<PieceAttachmentResponseDto>> list(
            @PathVariable String orgSlug,
            @PathVariable Integer pieceId,
            @RequestParam(value = "kind", required = false) PieceAttachmentKind kind
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        List<PieceAttachmentResponseDto> out = attachmentService.list(orgId, pieceId, kind).stream()
                .map(PieceAttachmentResponseDto::from)
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{attachmentId}/download")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<Void> download(
            @PathVariable String orgSlug,
            @PathVariable Integer pieceId,
            @PathVariable Integer attachmentId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        PresignedDownload presigned = attachmentService.presign(orgId, pieceId, attachmentId);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(presigned.url())).build();
    }

    /**
     * Streams the original bytes through the backend. The download endpoint redirects to a
     * presigned R2 URL, which only the origins allowed by the bucket's CORS can fetch and which
     * Spanish ISPs block during LaLiga matches; the timeline editor (another origin) and the
     * frontend read attachments from here instead. An attachment never changes (a new file is a
     * new row), so the response is immutable for a year in the user's cache and {@code If-None-Match}
     * gets a 304 without touching R2.
     */
    @GetMapping("/{attachmentId}/content")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<Resource> content(
            @PathVariable String orgSlug,
            @PathVariable Integer pieceId,
            @PathVariable Integer attachmentId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        PieceAttachment attachment = attachmentService.requireInPiece(orgId, pieceId, attachmentId);
        return serveOriginal(attachment, ifNoneMatch, IMMUTABLE);
    }

    /**
     * Serves the JPEG thumbnail of an IMAGE (long side {@code stocka.pieces.attachment.thumbnail-max-side}
     * px), immutable for a year like the original. Without a thumbnail (other kinds, images not yet
     * backfilled, undecodable images) it serves the original with a short cache instead, so the
     * client picks up the thumbnail once the backfill has generated it.
     */
    @GetMapping("/{attachmentId}/thumbnail")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<Resource> thumbnail(
            @PathVariable String orgSlug,
            @PathVariable Integer pieceId,
            @PathVariable Integer attachmentId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        PieceAttachment attachment = attachmentService.requireInPiece(orgId, pieceId, attachmentId);
        if (!attachment.hasThumbnail()) {
            return serveOriginal(attachment, ifNoneMatch, FALLBACK);
        }
        String etag = thumbnailEtag(attachment);
        if (etagMatches(ifNoneMatch, etag)) {
            return notModified(etag, IMMUTABLE);
        }
        AttachmentContent content = attachmentService.openThumbnail(attachment);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(attachment.getThumbSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        PieceAttachmentService.inlineDispositionFor(thumbnailFilename(attachment)))
                .cacheControl(IMMUTABLE)
                .eTag(etag)
                .body(new InputStreamResource(content.stream()));
    }

    private ResponseEntity<Resource> serveOriginal(PieceAttachment attachment, String ifNoneMatch,
                                                   CacheControl cacheControl) {
        String etag = originalEtag(attachment);
        if (etagMatches(ifNoneMatch, etag)) {
            return notModified(etag, cacheControl);
        }
        AttachmentContent content = attachmentService.openContent(attachment);
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(attachment.getMimeType()))
                .contentLength(attachment.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        PieceAttachmentService.inlineDispositionFor(attachment.getOriginalFilename()))
                .cacheControl(cacheControl)
                .eTag(etag)
                .body(new InputStreamResource(content.stream()));
    }

    /** Strong ETag of the original of an attachment: its id never names other bytes. */
    static String originalEtag(PieceAttachment attachment) {
        return "\"att-" + attachment.getId() + "-orig\"";
    }

    /** Strong ETag of the thumbnail of an attachment, distinct from the original's. */
    static String thumbnailEtag(PieceAttachment attachment) {
        return "\"att-" + attachment.getId() + "-thumb\"";
    }

    /**
     * Whether an {@code If-None-Match} header names {@code etag}: a comma-separated list of
     * (possibly weak) entity tags, or {@code *}.
     */
    static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String token : ifNoneMatch.split(",")) {
            String candidate = token.trim();
            if (candidate.equals("*")) {
                return true;
            }
            if (candidate.startsWith("W/")) {
                candidate = candidate.substring(2);
            }
            if (candidate.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<Resource> notModified(String etag, CacheControl cacheControl) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(etag)
                .cacheControl(cacheControl)
                .build();
    }

    private static MediaType mediaTypeOf(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** {@code photo.png} becomes {@code photo.thumb.jpg}: the name the browser saves it under. */
    private static String thumbnailFilename(PieceAttachment attachment) {
        String name = attachment.getOriginalFilename() == null ? "file" : attachment.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + ".thumb.jpg";
    }

    @DeleteMapping("/{attachmentId}")
    @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")
    public ResponseEntity<Void> delete(
            @PathVariable String orgSlug,
            @PathVariable Integer pieceId,
            @PathVariable Integer attachmentId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        attachmentService.softDelete(orgId, pieceId, attachmentId);
        return ResponseEntity.noContent().build();
    }
}
