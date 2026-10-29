package com.stocka.backend.modules.pieces.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationQuotaProperties;
import com.stocka.backend.modules.pieces.dto.ThumbnailBackfillReport;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.storage.R2ObjectNotFoundException;
import com.stocka.backend.modules.storage.R2Properties;
import com.stocka.backend.modules.storage.R2Service;
import com.stocka.backend.modules.storage.R2UnavailableException;
import com.stocka.backend.modules.storage.UploadedObject;

/**
 * Thumbnail handling of {@link PieceAttachmentService}: generation and storage at upload, the
 * fallbacks when it fails, deletion, the streams the controller opens and the admin backfill.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PieceAttachmentService thumbnails")
class PieceAttachmentServiceThumbnailTest {
    private static final String ORIGINAL_KEY = "org/7/piece/42/uuid-photo.png";
    private static final String THUMB_KEY = ORIGINAL_KEY + ".thumb.jpg";
    private static final byte[] THUMB_BYTES = {1, 2, 3, 4, 5};

    @Mock PieceAttachmentRepository attachmentRepository;
    @Mock PieceRepository pieceRepository;
    @Mock PieceService pieceService;
    @Mock PieceHistoryService historyService;
    @Mock R2Service r2Service;
    @Mock R2Properties r2Properties;
    @Mock AttachmentThumbnailService thumbnails;

    private PieceAttachmentService sut;
    private Piece piece;

    @BeforeEach
    void setUp() {
        PieceAttachmentProperties limits = new PieceAttachmentProperties();
        limits.setAllowedImageMimes(Set.of("image/png", "image/jpeg"));
        OrganizationQuotaProperties quotas = new OrganizationQuotaProperties();
        sut = new PieceAttachmentService(
                attachmentRepository, pieceRepository, pieceService,
                historyService, r2Service, r2Properties, limits, quotas, thumbnails);
        piece = new Piece().setId(42).setOrganization(new Organization().setId(7));
    }

    private static byte[] pngBytes() {
        try {
            BufferedImage img = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Thumbnail thumbnail() {
        return new Thumbnail(THUMB_BYTES, 8, 4, 800, 400);
    }

    private static PieceAttachment attachment(int id, String thumbKey) {
        return new PieceAttachment()
                .setId(id)
                .setKind(PieceAttachmentKind.IMAGE)
                .setR2Key("org/7/piece/42/" + id + "-photo.png")
                .setOriginalFilename(id + "-photo.png")
                .setMimeType("image/png")
                .setSizeBytes(100)
                .setThumbKey(thumbKey);
    }

    @Nested
    @DisplayName("upload")
    class Upload {
        @BeforeEach
        void stubUploadPipeline() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            when(r2Service.buildPieceKey(eq(7), eq(42), anyString())).thenReturn(ORIGINAL_KEY);
            when(attachmentRepository.save(any(PieceAttachment.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        private void stubUploads() {
            when(r2Service.upload(anyString(), any(InputStream.class), anyLong(), anyString()))
                    .thenAnswer(inv -> new UploadedObject(inv.getArgument(0), inv.getArgument(2), inv.getArgument(3)));
        }

        private PieceAttachment uploadImage() {
            MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", pngBytes());
            return sut.upload(7, 42, PieceAttachmentKind.IMAGE, file);
        }

        @Test
        @DisplayName("an IMAGE gets its thumbnail stored next to the original and described on the row")
        void storesThumbnail() throws IOException {
            stubUploads();
            when(thumbnails.generate(any())).thenReturn(Optional.of(thumbnail()));

            PieceAttachment saved = uploadImage();

            assertThat(saved.getThumbKey()).isEqualTo(THUMB_KEY);
            assertThat(saved.getThumbWidth()).isEqualTo(8);
            assertThat(saved.getThumbHeight()).isEqualTo(4);
            assertThat(saved.getThumbSizeBytes()).isEqualTo((long) THUMB_BYTES.length);
            assertThat(saved.getWidth()).isEqualTo(800);
            assertThat(saved.getHeight()).isEqualTo(400);
            assertThat(saved.hasThumbnail()).isTrue();
            verify(r2Service).upload(eq(ORIGINAL_KEY), any(InputStream.class), anyLong(), eq("image/png"));
            verify(r2Service).upload(eq(THUMB_KEY), any(InputStream.class), eq((long) THUMB_BYTES.length), eq("image/jpeg"));
        }

        @Test
        @DisplayName("the thumbnail bytes uploaded are the generated JPEG")
        void uploadsGeneratedBytes() throws IOException {
            stubUploads();
            when(thumbnails.generate(any())).thenReturn(Optional.of(thumbnail()));

            uploadImage();

            ArgumentCaptor<InputStream> streams = ArgumentCaptor.forClass(InputStream.class);
            verify(r2Service, times(2)).upload(anyString(), streams.capture(), anyLong(), anyString());
            assertThat(streams.getAllValues().get(1).readAllBytes()).isEqualTo(THUMB_BYTES);
        }

        @Test
        @DisplayName("when no reader understands the image the upload succeeds without thumbnail")
        void generatorEmpty() throws IOException {
            stubUploads();
            when(thumbnails.generate(any())).thenReturn(Optional.empty());

            PieceAttachment saved = uploadImage();

            assertThat(saved.hasThumbnail()).isFalse();
            assertThat(saved.getWidth()).isNull();
            assertThat(saved.getThumbSizeBytes()).isNull();
            verify(r2Service, times(1)).upload(anyString(), any(InputStream.class), anyLong(), anyString());
        }

        @Test
        @DisplayName("when the generator throws IOException the upload succeeds without thumbnail")
        void generatorIoFailure() throws IOException {
            stubUploads();
            when(thumbnails.generate(any())).thenThrow(new IOException("corrupt"));

            PieceAttachment saved = uploadImage();

            assertThat(saved.hasThumbnail()).isFalse();
            verify(r2Service, times(1)).upload(anyString(), any(InputStream.class), anyLong(), anyString());
        }

        @Test
        @DisplayName("when the generator throws a RuntimeException the upload succeeds without thumbnail")
        void generatorRuntimeFailure() throws IOException {
            stubUploads();
            when(thumbnails.generate(any())).thenThrow(new IllegalStateException("decoder bug"));

            PieceAttachment saved = uploadImage();

            assertThat(saved.hasThumbnail()).isFalse();
        }

        @Test
        @DisplayName("when R2 refuses the thumbnail the row keeps the image size but no thumbnail")
        void thumbnailUploadFails() throws IOException {
            when(r2Service.upload(eq(ORIGINAL_KEY), any(InputStream.class), anyLong(), anyString()))
                    .thenAnswer(inv -> new UploadedObject(inv.getArgument(0), inv.getArgument(2), inv.getArgument(3)));
            when(r2Service.upload(eq(THUMB_KEY), any(InputStream.class), anyLong(), anyString()))
                    .thenThrow(new R2UnavailableException("down", null));
            when(thumbnails.generate(any())).thenReturn(Optional.of(thumbnail()));

            PieceAttachment saved = uploadImage();

            assertThat(saved.hasThumbnail()).isFalse();
            assertThat(saved.getThumbWidth()).isNull();
            assertThat(saved.getWidth()).isEqualTo(800);
            assertThat(saved.getHeight()).isEqualTo(400);
        }

        @Test
        @DisplayName("the thumbnail is generated from the uploaded bytes")
        void generatedFromUploadedBytes() throws IOException {
            stubUploads();
            byte[] png = pngBytes();
            when(thumbnails.generate(png)).thenReturn(Optional.of(thumbnail()));

            MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", png);
            PieceAttachment saved = sut.upload(7, 42, PieceAttachmentKind.IMAGE, file);

            assertThat(saved.hasThumbnail()).isTrue();
        }

        @Test
        @DisplayName("a DOCUMENT never goes through the generator")
        void documentSkipsGenerator() throws IOException {
            stubUploads();
            MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
                    "hello".getBytes(StandardCharsets.UTF_8));

            PieceAttachment saved = sut.upload(7, 42, PieceAttachmentKind.DOCUMENT, file);

            assertThat(saved.hasThumbnail()).isFalse();
            verify(thumbnails, never()).generate(any());
            verify(r2Service, times(1)).upload(anyString(), any(InputStream.class), anyLong(), anyString());
        }
    }

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {
        @BeforeEach
        void stubLookup() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
        }

        private PieceAttachment stored(String thumbKey) {
            PieceAttachment attachment = attachment(5, thumbKey).setPiece(piece);
            when(attachmentRepository.findById(5)).thenReturn(Optional.of(attachment));
            return attachment;
        }

        @Test
        @DisplayName("removes the thumbnail object together with the original")
        void deletesBoth() {
            PieceAttachment attachment = stored(THUMB_KEY);

            sut.softDelete(7, 42, 5);

            verify(r2Service).delete(attachment.getR2Key());
            verify(r2Service).delete(THUMB_KEY);
            assertThat(attachment.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("without a thumbnail only the original is removed")
        void deletesOriginalOnly() {
            PieceAttachment attachment = stored(null);

            sut.softDelete(7, 42, 5);

            verify(r2Service, times(1)).delete(anyString());
            verify(r2Service).delete(attachment.getR2Key());
        }

        @Test
        @DisplayName("an R2 failure on the thumbnail is logged and the soft-delete still completes")
        void toleratesThumbnailDeleteFailure() {
            PieceAttachment attachment = stored(THUMB_KEY);
            org.mockito.Mockito.lenient()
                    .doThrow(new R2UnavailableException("down", null)).when(r2Service).delete(THUMB_KEY);

            sut.softDelete(7, 42, 5);

            assertThat(attachment.getDeletedAt()).isNotNull();
            verify(historyService).recordAttachmentRemoved(eq(piece), any(), eq(attachment.getOriginalFilename()));
        }
    }

    @Nested
    @DisplayName("opening streams")
    class Streams {
        @Test
        @DisplayName("openThumbnail streams the thumbnail key")
        void openThumbnail() {
            PieceAttachment attachment = attachment(5, THUMB_KEY);
            InputStream stream = new ByteArrayInputStream(THUMB_BYTES);
            when(r2Service.open(THUMB_KEY)).thenReturn(stream);

            AttachmentContent content = sut.openThumbnail(attachment);

            assertThat(content.stream()).isSameAs(stream);
            assertThat(content.attachment()).isSameAs(attachment);
        }

        @Test
        @DisplayName("openThumbnail without a thumbnail is a programming error")
        void openThumbnailMissing() {
            assertThatThrownBy(() -> sut.openThumbnail(attachment(5, null)))
                    .isInstanceOf(IllegalStateException.class);
            verify(r2Service, never()).open(anyString());
        }

        @Test
        @DisplayName("openContent(attachment) streams the original key")
        void openContent() {
            PieceAttachment attachment = attachment(5, THUMB_KEY);
            InputStream stream = new ByteArrayInputStream(new byte[] {9});
            when(r2Service.open(attachment.getR2Key())).thenReturn(stream);

            assertThat(sut.openContent(attachment).stream()).isSameAs(stream);
        }

        @Test
        @DisplayName("requireInPiece returns the attachment of the piece")
        void requireInPiece() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            PieceAttachment attachment = attachment(5, null).setPiece(piece);
            when(attachmentRepository.findById(5)).thenReturn(Optional.of(attachment));

            assertThat(sut.requireInPiece(7, 42, 5)).isSameAs(attachment);
        }

        @Test
        @DisplayName("requireInPiece is 404 when the attachment belongs to another piece")
        void requireInPieceOtherPiece() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            Piece other = new Piece().setId(43).setOrganization(piece.getOrganization());
            when(attachmentRepository.findById(5)).thenReturn(Optional.of(attachment(5, null).setPiece(other)));

            assertThatThrownBy(() -> sut.requireInPiece(7, 42, 5))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("requireInPiece is 404 when the attachment does not exist")
        void requireInPieceMissing() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            when(attachmentRepository.findById(5)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.requireInPiece(7, 42, 5)).isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("backfillThumbnails")
    class Backfill {
        private void pending(PieceAttachment... attachments) {
            when(attachmentRepository.findByKindWithoutThumbnail(eq(PieceAttachmentKind.IMAGE), any(Pageable.class)))
                    .thenReturn(List.of(attachments));
        }

        @Test
        @DisplayName("generates, stores and saves each pending image; failures are reported and retried later")
        void mixedBatch() throws IOException {
            PieceAttachment ok = attachment(1, null);
            PieceAttachment gone = attachment(2, null);
            PieceAttachment undecodable = attachment(3, null);
            pending(ok, gone, undecodable);
            when(r2Service.open(ok.getR2Key())).thenReturn(new ByteArrayInputStream(new byte[] {1}));
            when(r2Service.open(gone.getR2Key())).thenThrow(new R2ObjectNotFoundException("gone", null));
            when(r2Service.open(undecodable.getR2Key())).thenReturn(new ByteArrayInputStream(new byte[] {2}));
            when(thumbnails.generate(new byte[] {1})).thenReturn(Optional.of(thumbnail()));
            when(thumbnails.generate(new byte[] {2})).thenReturn(Optional.empty());
            when(r2Service.upload(eq(ok.getR2Key() + ".thumb.jpg"), any(InputStream.class), anyLong(), eq("image/jpeg")))
                    .thenAnswer(inv -> new UploadedObject(inv.getArgument(0), inv.getArgument(2), inv.getArgument(3)));
            when(attachmentRepository.countByKindWithoutThumbnail(PieceAttachmentKind.IMAGE)).thenReturn(2L);

            ThumbnailBackfillReport report = sut.backfillThumbnails(50);

            assertThat(report.processed()).isEqualTo(3);
            assertThat(report.generated()).isEqualTo(1);
            assertThat(report.failed()).containsExactly(2, 3);
            assertThat(report.remaining()).isEqualTo(2);
            assertThat(ok.getThumbKey()).isEqualTo(ok.getR2Key() + ".thumb.jpg");
            assertThat(ok.getThumbSizeBytes()).isEqualTo((long) THUMB_BYTES.length);
            verify(attachmentRepository, times(1)).save(any(PieceAttachment.class));
            verify(attachmentRepository).save(ok);
        }

        @Test
        @DisplayName("a decoder exception marks the attachment as failed without aborting the batch")
        void decoderThrows() throws IOException {
            PieceAttachment broken = attachment(1, null);
            PieceAttachment ok = attachment(2, null);
            pending(broken, ok);
            when(r2Service.open(anyString())).thenAnswer(inv -> new ByteArrayInputStream(new byte[] {7}));
            when(thumbnails.generate(any())).thenThrow(new IOException("corrupt")).thenReturn(Optional.of(thumbnail()));
            when(r2Service.upload(anyString(), any(InputStream.class), anyLong(), anyString()))
                    .thenAnswer(inv -> new UploadedObject(inv.getArgument(0), inv.getArgument(2), inv.getArgument(3)));

            ThumbnailBackfillReport report = sut.backfillThumbnails(10);

            assertThat(report.generated()).isEqualTo(1);
            assertThat(report.failed()).containsExactly(1);
            verify(attachmentRepository).save(ok);
        }

        @Test
        @DisplayName("an R2 refusal of the thumbnail upload counts as failed and nothing is saved")
        void thumbnailUploadFails() throws IOException {
            PieceAttachment a = attachment(1, null);
            pending(a);
            when(r2Service.open(a.getR2Key())).thenReturn(new ByteArrayInputStream(new byte[] {1}));
            when(thumbnails.generate(any())).thenReturn(Optional.of(thumbnail()));
            when(r2Service.upload(anyString(), any(InputStream.class), anyLong(), anyString()))
                    .thenThrow(new R2UnavailableException("down", null));

            ThumbnailBackfillReport report = sut.backfillThumbnails(10);

            assertThat(report.generated()).isZero();
            assertThat(report.failed()).containsExactly(1);
            verify(attachmentRepository, never()).save(any(PieceAttachment.class));
        }

        @Test
        @DisplayName("an unreadable stream counts as failed")
        void streamReadFails() throws IOException {
            PieceAttachment a = attachment(1, null);
            pending(a);
            InputStream broken = new InputStream() {
                @Override public int read() throws IOException { throw new IOException("reset"); }
            };
            when(r2Service.open(a.getR2Key())).thenReturn(broken);

            ThumbnailBackfillReport report = sut.backfillThumbnails(10);

            assertThat(report.failed()).containsExactly(1);
            verify(thumbnails, never()).generate(any());
        }

        @Test
        @DisplayName("nothing pending gives an empty report")
        void nothingPending() {
            pending();
            when(attachmentRepository.countByKindWithoutThumbnail(PieceAttachmentKind.IMAGE)).thenReturn(0L);

            ThumbnailBackfillReport report = sut.backfillThumbnails(10);

            assertThat(report.processed()).isZero();
            assertThat(report.generated()).isZero();
            assertThat(report.failed()).isEmpty();
            assertThat(report.remaining()).isZero();
        }

        @Test
        @DisplayName("the batch size is clamped to 1..MAX_BACKFILL_BATCH")
        void batchClamped() {
            pending();
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

            sut.backfillThumbnails(0);
            sut.backfillThumbnails(-5);
            sut.backfillThumbnails(10_000);
            sut.backfillThumbnails(25);

            verify(attachmentRepository, times(4)).findByKindWithoutThumbnail(eq(PieceAttachmentKind.IMAGE), pageable.capture());
            assertThat(pageable.getAllValues().stream().map(Pageable::getPageSize).toList())
                    .containsExactly(1, 1, PieceAttachmentService.MAX_BACKFILL_BATCH, 25);
            assertThat(pageable.getAllValues().get(0).getPageNumber()).isZero();
        }

        @Test
        @DisplayName("only IMAGE attachments are considered")
        void onlyImages() {
            pending();

            sut.backfillThumbnails(10);

            verify(attachmentRepository).findByKindWithoutThumbnail(eq(PieceAttachmentKind.IMAGE), any(Pageable.class));
            verify(attachmentRepository).countByKindWithoutThumbnail(PieceAttachmentKind.IMAGE);
            verify(attachmentRepository, never()).findByKindWithoutThumbnail(eq(PieceAttachmentKind.AUDIO), any(Pageable.class));
        }

        @Test
        @DisplayName("the piece id passed to the generator is irrelevant: bytes come straight from R2")
        void bytesFromR2() throws IOException {
            PieceAttachment a = attachment(1, null);
            pending(a);
            byte[] original = {4, 5, 6};
            when(r2Service.open(a.getR2Key())).thenReturn(new ByteArrayInputStream(original));
            when(thumbnails.generate(any())).thenReturn(Optional.empty());

            sut.backfillThumbnails(anyBatch());

            verify(thumbnails).generate(original);
        }

        private int anyBatch() {
            return 10;
        }
    }
}
