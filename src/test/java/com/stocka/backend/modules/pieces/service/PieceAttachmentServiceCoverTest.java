package com.stocka.backend.modules.pieces.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.storage.R2Properties;
import com.stocka.backend.modules.storage.R2Service;
import com.stocka.backend.modules.storage.UploadedObject;

@ExtendWith(MockitoExtension.class)
@DisplayName("PieceAttachmentService cover linkage")
class PieceAttachmentServiceCoverTest {

    @Mock PieceAttachmentRepository attachmentRepository;
    @Mock PieceRepository pieceRepository;
    @Mock PieceService pieceService;
    @Mock PieceHistoryService historyService;
    @Mock R2Service r2Service;
    @Mock R2Properties r2Properties;

    private PieceAttachmentService sut;
    private Piece piece;

    @BeforeEach
    void setUp() {
        PieceAttachmentProperties limits = new PieceAttachmentProperties();
        limits.setAllowedImageMimes(Set.of("image/png", "image/jpeg"));
        sut = new PieceAttachmentService(
                attachmentRepository, pieceRepository, pieceService,
                historyService, r2Service, r2Properties, limits);
        piece = new Piece().setId(42).setOrganization(new Organization().setId(7));
    }

    private MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3});
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("auto-marks the first IMAGE attachment as cover")
        void should_setCover_whenPieceHasNoCover() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            when(r2Service.buildPieceKey(7, 42, "photo.png")).thenReturn("orgs/7/pieces/42/photo.png");
            when(r2Service.upload(anyString(), any(ByteArrayInputStream.class), anyLong(), anyString()))
                    .thenReturn(new UploadedObject("orgs/7/pieces/42/photo.png", 3L, "image/png"));
            when(attachmentRepository.countByPieceAndKind(piece, PieceAttachmentKind.IMAGE)).thenReturn(0L);
            when(attachmentRepository.save(any(PieceAttachment.class)))
                    .thenAnswer(inv -> ((PieceAttachment) inv.getArgument(0)).setId(99));

            PieceAttachment saved = sut.upload(7, 42, PieceAttachmentKind.IMAGE, pngFile());

            assertThat(piece.getCoverAttachment()).isSameAs(saved);
            verify(pieceRepository).save(piece);
        }

        @Test
        @DisplayName("does not change cover when piece already has one")
        void should_keepCover_whenPieceAlreadyHasOne() {
            PieceAttachment existingCover = new PieceAttachment().setId(50);
            piece.setCoverAttachment(existingCover);
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            when(r2Service.buildPieceKey(anyInt(), anyInt(), anyString())).thenReturn("k");
            when(r2Service.upload(anyString(), any(ByteArrayInputStream.class), anyLong(), anyString()))
                    .thenReturn(new UploadedObject("k", 3L, "image/png"));
            when(attachmentRepository.countByPieceAndKind(piece, PieceAttachmentKind.IMAGE)).thenReturn(1L);
            when(attachmentRepository.save(any(PieceAttachment.class)))
                    .thenAnswer(inv -> ((PieceAttachment) inv.getArgument(0)).setId(60));

            sut.upload(7, 42, PieceAttachmentKind.IMAGE, pngFile());

            assertThat(piece.getCoverAttachment()).isSameAs(existingCover);
            verify(pieceRepository, never()).save(piece);
        }

        @Test
        @DisplayName("does not promote DOCUMENT uploads to cover")
        void should_notSetCover_forDocumentKind() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            when(r2Service.buildPieceKey(anyInt(), anyInt(), anyString())).thenReturn("k");
            when(r2Service.upload(anyString(), any(ByteArrayInputStream.class), anyLong(), anyString()))
                    .thenReturn(new UploadedObject("k", 3L, "image/png"));
            when(attachmentRepository.save(any(PieceAttachment.class)))
                    .thenAnswer(inv -> ((PieceAttachment) inv.getArgument(0)).setId(70));

            MockMultipartFile pdf = new MockMultipartFile("file", "doc.pdf",
                    "application/pdf", new byte[]{1, 2, 3});
            sut.upload(7, 42, PieceAttachmentKind.DOCUMENT, pdf);

            assertThat(piece.getCoverAttachment()).isNull();
            verify(pieceRepository, never()).save(piece);
        }
    }

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {

        @Test
        @DisplayName("clears cover on the piece when the deleted attachment was the cover")
        void should_clearCover_whenDeletingTheCover() {
            PieceAttachment cover = new PieceAttachment()
                    .setId(99)
                    .setKind(PieceAttachmentKind.IMAGE)
                    .setR2Key("k")
                    .setOriginalFilename("photo.png")
                    .setPiece(piece);
            piece.setCoverAttachment(cover);
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            when(attachmentRepository.findById(99)).thenReturn(java.util.Optional.of(cover));
            when(attachmentRepository.save(any(PieceAttachment.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            sut.softDelete(7, 42, 99);

            assertThat(piece.getCoverAttachment()).isNull();
            ArgumentCaptor<Piece> pieceCaptor = ArgumentCaptor.forClass(Piece.class);
            verify(pieceRepository, times(1)).save(pieceCaptor.capture());
            assertThat(pieceCaptor.getValue().getCoverAttachment()).isNull();
        }

        @Test
        @DisplayName("does not touch cover when deleting a non-cover attachment")
        void should_keepCover_whenDeletingOtherImage() {
            PieceAttachment cover = new PieceAttachment().setId(50)
                    .setKind(PieceAttachmentKind.IMAGE).setR2Key("c").setOriginalFilename("c.png")
                    .setPiece(piece);
            PieceAttachment other = new PieceAttachment().setId(60)
                    .setKind(PieceAttachmentKind.IMAGE).setR2Key("o").setOriginalFilename("o.png")
                    .setPiece(piece);
            piece.setCoverAttachment(cover);
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            when(attachmentRepository.findById(60)).thenReturn(java.util.Optional.of(other));
            when(attachmentRepository.save(any(PieceAttachment.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            sut.softDelete(7, 42, 60);

            assertThat(piece.getCoverAttachment()).isSameAs(cover);
            verify(pieceRepository, never()).save(any(Piece.class));
        }
    }
}
