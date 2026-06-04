package com.stocka.backend.modules.sync.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.service.PieceAttachmentService;
import com.stocka.backend.modules.sync.dto.AttachmentSyncDto;
import com.stocka.backend.modules.sync.dto.SyncChangesResponse;
import com.stocka.backend.modules.sync.dto.SyncMutationRequest;
import com.stocka.backend.modules.sync.dto.SyncMutationsResponse;
import com.stocka.backend.modules.sync.service.SyncPushService;
import com.stocka.backend.modules.sync.service.SyncService;

/**
 * Offline synchronization endpoints (v1) for the desktop client.
 *
 * <p>The pull feed returns documents changed since the client's per-collection checkpoint,
 * tombstones included, so the local RxDB store can converge. Reads are allowed for any
 * organization member (including SPECTATOR).
 *
 * @since 0.2.0
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/sync/v1")
public class SyncController {

    private final SyncService syncService;
    private final SyncPushService syncPushService;
    private final PieceAttachmentService attachmentService;
    private final OrganizationResolver orgResolver;

    public SyncController(SyncService syncService, SyncPushService syncPushService,
                         PieceAttachmentService attachmentService, OrganizationResolver orgResolver) {
        this.syncService = syncService;
        this.syncPushService = syncPushService;
        this.attachmentService = attachmentService;
        this.orgResolver = orgResolver;
    }

    /**
     * Returns the next page of changes for the organization.
     *
     * @param orgSlug organization slug
     * @param since   per-collection checkpoint as {@code "collection:rev,collection:rev"} (optional)
     * @param limit   requested page size (optional; clamped server-side)
     * @return the changed documents, advanced checkpoint and {@code hasMore} flag
     */
    @GetMapping("/changes")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<SyncChangesResponse> changes(
            @PathVariable String orgSlug,
            @RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "limit", defaultValue = "0") int limit) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        SyncChangesResponse response = syncService.pull(orgId, SyncService.parseCheckpoint(since), limit);
        return ResponseEntity.ok(response);
    }

    /**
     * Applies a batch of offline mutations. Endpoint access requires any member; each mutation is
     * authorized individually by collection inside the service (e.g. locations require
     * OWNER/MANAGER), so a forbidden mutation is rejected without failing the batch.
     *
     * @param orgSlug organization slug
     * @param request the mutation batch
     * @return one result per mutation
     */
    @PostMapping("/mutations")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<SyncMutationsResponse> mutations(
            @PathVariable String orgSlug,
            @RequestBody SyncMutationRequest request) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        return ResponseEntity.ok(syncPushService.push(orgId, orgSlug, request));
    }

    /**
     * Uploads a binary that was queued offline. The parent piece is addressed by its {@code syncId}
     * and the client provides the attachment's own {@code syncId}, so the upload is idempotent and
     * the client can reconcile local metadata. Binaries ride a queue separate from the data
     * mutations so a large file never blocks data convergence (R15–R17).
     *
     * @param orgSlug          organization slug
     * @param pieceSyncId      parent piece's client-stable identity
     * @param attachmentSyncId the attachment's client-assigned identity
     * @param kind             attachment kind (IMAGE or DOCUMENT)
     * @param file             the uploaded binary
     * @return the canonical attachment metadata (with its server {@code rev})
     */
    @PostMapping(value = "/attachments", consumes = "multipart/form-data")
    @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")
    public ResponseEntity<AttachmentSyncDto> uploadAttachment(
            @PathVariable String orgSlug,
            @RequestParam("pieceSyncId") String pieceSyncId,
            @RequestParam("attachmentSyncId") String attachmentSyncId,
            @RequestParam("kind") PieceAttachmentKind kind,
            @RequestPart("file") MultipartFile file) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        PieceAttachment saved = attachmentService.uploadForSync(orgId, pieceSyncId, attachmentSyncId, kind, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(AttachmentSyncDto.from(saved));
    }

    /**
     * Soft-deletes an attachment by its {@code syncId} (offline delete replay). Idempotent.
     *
     * @param orgSlug          organization slug
     * @param attachmentSyncId the attachment's client-stable identity
     * @return {@code 204 No Content}
     */
    @DeleteMapping("/attachments/{attachmentSyncId}")
    @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")
    public ResponseEntity<Void> deleteAttachment(
            @PathVariable String orgSlug,
            @PathVariable String attachmentSyncId) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        attachmentService.softDeleteBySyncId(orgId, attachmentSyncId);
        return ResponseEntity.noContent().build();
    }
}
