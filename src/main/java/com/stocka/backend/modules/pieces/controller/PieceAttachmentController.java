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
     * Streams the attachment bytes through the backend. The download endpoint redirects to a
     * presigned R2 URL, which only the origins allowed by the bucket's CORS can fetch and which
     * Spanish ISPs block during LaLiga matches; the timeline editor (another origin) and the
     * frontend read attachments from here instead. Attachments are immutable, so the response may
     * be cached privately.
     */
    @GetMapping("/{attachmentId}/content")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<Resource> content(
            @PathVariable String orgSlug,
            @PathVariable Integer pieceId,
            @PathVariable Integer attachmentId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        AttachmentContent content = attachmentService.openContent(orgId, pieceId, attachmentId);
        PieceAttachment attachment = content.attachment();
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(attachment.getMimeType());
        } catch (InvalidMediaTypeException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(attachment.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        PieceAttachmentService.inlineDispositionFor(attachment.getOriginalFilename()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(24)).cachePrivate())
                .body(new InputStreamResource(content.stream()));
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
