package com.stocka.backend.modules.pieces.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.pieces.dto.ThumbnailBackfillReport;
import com.stocka.backend.modules.pieces.service.PieceAttachmentService;

/** Global-admin maintenance of piece attachments across every organization. */
@RestController
@RequestMapping("/admin/attachments")
public class AdminAttachmentController {
    private final PieceAttachmentService attachmentService;

    public AdminAttachmentController(PieceAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * Generates thumbnails for IMAGE attachments that have none (images uploaded before V32).
     * Processes one batch per call; repeat until {@code remaining} is {@code 0} (minus the ids in
     * {@code failed}, which stay pending).
     *
     * @param batch attachments per call, clamped to {@code 1..500}
     * @return the batch report
     */
    @PostMapping("/thumbnails/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ThumbnailBackfillReport> backfillThumbnails(
            @RequestParam(value = "batch", defaultValue = "50") int batch
    ) {
        return ResponseEntity.ok(attachmentService.backfillThumbnails(batch));
    }
}
