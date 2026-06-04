package com.stocka.backend.modules.pieces.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationQuotaProperties;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.storage.R2Properties;
import com.stocka.backend.modules.storage.R2Service;
import com.stocka.backend.modules.storage.UploadedObject;
import com.stocka.backend.modules.sync.support.SyncStamper;

/**
 * Verifies the offline-sync attachment paths: {@code uploadForSync} resolves the piece by
 * {@code syncId}, preserves the client-assigned attachment {@code syncId}, is idempotent, and
 * {@code softDeleteBySyncId} resolves and tombstones by {@code syncId}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PieceAttachmentService offline-sync paths")
class PieceAttachmentServiceSyncTest {

    @Mock PieceAttachmentRepository attachmentRepository;
    @Mock PieceRepository pieceRepository;
    @Mock PieceService pieceService;
    @Mock PieceHistoryService historyService;
    @Mock R2Service r2Service;
    @Mock R2Properties r2Properties;
    @Mock SyncStamper syncStamper;

    private PieceAttachmentService sut;
    private Piece piece;

    private static final String PIECE_SYNC_ID = "piece-sync-1";
    private static final String ATTACH_SYNC_ID = "attach-sync-1";

    @BeforeEach
    void setUp() {
        PieceAttachmentProperties limits = new PieceAttachmentProperties();
        limits.setAllowedImageMimes(Set.of("image/png", "image/jpeg"));
        OrganizationQuotaProperties quotas = new OrganizationQuotaProperties();
        sut = new PieceAttachmentService(
                attachmentRepository, pieceRepository, pieceService,
                historyService, r2Service, r2Properties, limits, quotas, syncStamper);
        piece = new Piece().setId(42).setOrganization(new Organization().setId(7));
        piece.setSyncId(PIECE_SYNC_ID);
    }

    @Test
    @DisplayName("uploadForSync resolves the piece by syncId and preserves the attachment syncId")
    void should_upload_with_preserved_syncId() {
        when(attachmentRepository.findBySyncId(ATTACH_SYNC_ID)).thenReturn(Optional.empty());
        when(pieceRepository.findBySyncId(PIECE_SYNC_ID)).thenReturn(Optional.of(piece));
        when(r2Service.buildPieceKey(7, 42, "doc.pdf")).thenReturn("orgs/7/pieces/42/doc.pdf");
        when(r2Service.upload(anyString(), any(ByteArrayInputStream.class), anyLong(), anyString()))
                .thenReturn(new UploadedObject("orgs/7/pieces/42/doc.pdf", 3L, "application/pdf"));
        when(attachmentRepository.save(any(PieceAttachment.class)))
                .thenAnswer(inv -> ((PieceAttachment) inv.getArgument(0)).setId(99));

        MockMultipartFile pdf = new MockMultipartFile("file", "doc.pdf",
                "application/pdf", new byte[]{1, 2, 3});
        PieceAttachment saved = sut.uploadForSync(7, PIECE_SYNC_ID, ATTACH_SYNC_ID,
                PieceAttachmentKind.DOCUMENT, pdf);

        assertThat(saved.getSyncId()).isEqualTo(ATTACH_SYNC_ID);
        ArgumentCaptor<PieceAttachment> captor = ArgumentCaptor.forClass(PieceAttachment.class);
        verify(attachmentRepository).save(captor.capture());
        assertThat(captor.getValue().getSyncId()).isEqualTo(ATTACH_SYNC_ID);
        verify(syncStamper).stamp(any(PieceAttachment.class));
    }

    @Test
    @DisplayName("uploadForSync is idempotent: a repeated syncId returns the existing attachment")
    void should_be_idempotent_on_repeat() {
        PieceAttachment existing = new PieceAttachment().setId(5).setPiece(piece);
        existing.setSyncId(ATTACH_SYNC_ID);
        when(attachmentRepository.findBySyncId(ATTACH_SYNC_ID)).thenReturn(Optional.of(existing));

        MockMultipartFile pdf = new MockMultipartFile("file", "doc.pdf",
                "application/pdf", new byte[]{1, 2, 3});
        PieceAttachment result = sut.uploadForSync(7, PIECE_SYNC_ID, ATTACH_SYNC_ID,
                PieceAttachmentKind.DOCUMENT, pdf);

        assertThat(result).isSameAs(existing);
        verify(r2Service, never()).upload(anyString(), any(), anyLong(), anyString());
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("uploadForSync rejects when the parent piece sync id is unknown")
    void should_reject_missing_piece() {
        when(attachmentRepository.findBySyncId(ATTACH_SYNC_ID)).thenReturn(Optional.empty());
        when(pieceRepository.findBySyncId(PIECE_SYNC_ID)).thenReturn(Optional.empty());

        MockMultipartFile pdf = new MockMultipartFile("file", "doc.pdf",
                "application/pdf", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> sut.uploadForSync(7, PIECE_SYNC_ID, ATTACH_SYNC_ID,
                PieceAttachmentKind.DOCUMENT, pdf))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("softDeleteBySyncId tombstones the attachment resolved by syncId")
    void should_soft_delete_by_syncId() {
        PieceAttachment attachment = new PieceAttachment().setId(99)
                .setKind(PieceAttachmentKind.DOCUMENT).setR2Key("k").setOriginalFilename("doc.pdf")
                .setPiece(piece);
        attachment.setSyncId(ATTACH_SYNC_ID);
        when(attachmentRepository.findBySyncId(ATTACH_SYNC_ID)).thenReturn(Optional.of(attachment));
        when(attachmentRepository.save(any(PieceAttachment.class))).thenAnswer(inv -> inv.getArgument(0));

        sut.softDeleteBySyncId(7, ATTACH_SYNC_ID);

        assertThat(attachment.getDeletedAt()).isNotNull();
        verify(syncStamper).stamp(attachment);
        verify(r2Service).delete("k");
    }

    @Test
    @DisplayName("softDeleteBySyncId is a no-op when the attachment is already gone")
    void should_noop_when_absent() {
        when(attachmentRepository.findBySyncId(ATTACH_SYNC_ID)).thenReturn(Optional.empty());

        sut.softDeleteBySyncId(7, ATTACH_SYNC_ID);

        verify(attachmentRepository, never()).save(any());
    }
}
