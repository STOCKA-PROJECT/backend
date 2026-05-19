package com.stocka.backend.modules.pieces.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
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
            @RequestPart("file") MultipartFile file
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        PieceAttachment attachment = attachmentService.upload(orgId, pieceId, kind, file);
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
