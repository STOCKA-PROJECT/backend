package com.stocka.backend.modules.pieces.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationQuotaProperties;
import com.stocka.backend.modules.pieces.dto.PieceAttachmentResponseDto;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.storage.R2Properties;
import com.stocka.backend.modules.storage.R2Service;
import com.stocka.backend.modules.storage.UploadedObject;

/**
 * AUDIO attachments: the formats the belén player reproduces, their own limits, the client-measured
 * duration, and the content stream the timeline editor reads.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PieceAttachmentService audio attachments")
class PieceAttachmentServiceAudioTest {

    @Mock PieceAttachmentRepository attachmentRepository;
    @Mock PieceRepository pieceRepository;
    @Mock PieceService pieceService;
    @Mock PieceHistoryService historyService;
    @Mock R2Service r2Service;
    @Mock R2Properties r2Properties;

    private PieceAttachmentProperties limits;
    private PieceAttachmentService sut;
    private Piece piece;

    @BeforeEach
    void setUp() {
        limits = new PieceAttachmentProperties();
        sut = new PieceAttachmentService(
                attachmentRepository, pieceRepository, pieceService,
                historyService, r2Service, r2Properties, limits, new OrganizationQuotaProperties());
        piece = new Piece().setId(42).setOrganization(new Organization().setId(7));
    }

    /** An MP3 with an ID3v2 tag header: Tika detects {@code audio/mpeg} from the first bytes. */
    static byte[] mp3Bytes() {
        byte[] out = new byte[64];
        byte[] id3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};
        System.arraycopy(id3, 0, out, 0, id3.length);
        return out;
    }

    /** A RIFF/WAVE header: Tika detects {@code audio/vnd.wave}. */
    static byte[] wavBytes() {
        byte[] out = new byte[64];
        byte[] riff = "RIFF".getBytes(StandardCharsets.US_ASCII);
        byte[] wave = "WAVEfmt ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(riff, 0, out, 0, 4);
        out[4] = 56; // chunk size (little endian)
        System.arraycopy(wave, 0, out, 8, wave.length);
        return out;
    }

    private static MockMultipartFile file(String name, String declaredType, byte[] bytes) {
        return new MockMultipartFile("file", name, declaredType, bytes);
    }

    private void stubStorage() {
        when(pieceService.findInOrg(7, 42)).thenReturn(piece);
        when(r2Service.buildPieceKey(anyInt(), anyInt(), anyString())).thenReturn("k");
        when(r2Service.upload(anyString(), any(ByteArrayInputStream.class), anyLong(), anyString()))
                .thenAnswer(inv -> new UploadedObject("k", inv.getArgument(2), inv.getArgument(3)));
        when(attachmentRepository.save(any(PieceAttachment.class)))
                .thenAnswer(inv -> ((PieceAttachment) inv.getArgument(0)).setId(99));
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("accepts an MP3 and stores the duration the client measured")
        void should_acceptMp3_andStoreDuration() {
            stubStorage();

            PieceAttachment saved = sut.upload(7, 42, PieceAttachmentKind.AUDIO, file("nature.mp3", "audio/mpeg", mp3Bytes()), 61_000L);

            assertThat(saved.getKind()).isEqualTo(PieceAttachmentKind.AUDIO);
            assertThat(saved.getMimeType()).isEqualTo("audio/mpeg");
            assertThat(saved.getDurationMs()).isEqualTo(61_000L);
            assertThat(saved.getOriginalFilename()).isEqualTo("nature.mp3");
            verify(r2Service).upload(eq("k"), any(ByteArrayInputStream.class), eq(64L), eq("audio/mpeg"));
            verify(historyService).recordAttachmentAdded(eq(piece), any(), eq("nature.mp3"));
        }

        @Test
        @DisplayName("accepts a WAV (detected as audio/vnd.wave whatever the client declared)")
        void should_acceptWav() {
            stubStorage();

            PieceAttachment saved = sut.upload(7, 42, PieceAttachmentKind.AUDIO, file("beep.wav", "application/octet-stream", wavBytes()), null);

            assertThat(saved.getMimeType()).isEqualTo("audio/vnd.wave");
            assertThat(saved.getDurationMs()).isNull();
        }

        @Test
        @DisplayName("rejects bytes that are not audio, even when declared as audio/mpeg")
        void should_rejectNonAudioBytes() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            byte[] png = PieceAttachmentServiceCoverTest.pngBytes(1, 1);

            assertThatThrownBy(() -> sut.upload(7, 42, PieceAttachmentKind.AUDIO, file("song.mp3", "audio/mpeg", png), 1000L))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> {
                        ApiException api = (ApiException) e;
                        assertThat(api.getCode()).isEqualTo(ErrorCodes.UPLOAD_INVALID_KIND);
                        assertThat(api.getParams()).containsEntry("mime", "image/png");
                    });
            verify(r2Service, never()).upload(anyString(), any(), anyLong(), anyString());
        }

        @Test
        @DisplayName("rejects a file above the audio size limit")
        void should_rejectOversizedAudio() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            limits.setMaxAudioBytes(10);

            assertThatThrownBy(() -> sut.upload(7, 42, PieceAttachmentKind.AUDIO, file("big.mp3", "audio/mpeg", mp3Bytes()), null))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> {
                        ApiException api = (ApiException) e;
                        assertThat(api.getCode()).isEqualTo(ErrorCodes.UPLOAD_TOO_LARGE);
                        assertThat(api.getParams()).containsEntry("max", 10L);
                    });
        }

        @Test
        @DisplayName("rejects the upload when the piece already holds the maximum number of audios")
        void should_rejectWhenPieceIsFull() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            limits.setMaxAudiosPerPiece(2);
            when(attachmentRepository.countByPieceAndKind(piece, PieceAttachmentKind.AUDIO)).thenReturn(2L);

            assertThatThrownBy(() -> sut.upload(7, 42, PieceAttachmentKind.AUDIO, file("c.mp3", "audio/mpeg", mp3Bytes()), null))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode())
                    .isEqualTo(ErrorCodes.UPLOAD_TOO_LARGE);
        }

        @Test
        @DisplayName("the audio count limit is independent from images and documents")
        void should_countOnlyAudios() {
            stubStorage();
            limits.setMaxAudiosPerPiece(1);
            when(attachmentRepository.countByPieceAndKind(piece, PieceAttachmentKind.AUDIO)).thenReturn(0L);

            PieceAttachment saved = sut.upload(7, 42, PieceAttachmentKind.AUDIO, file("c.mp3", "audio/mpeg", mp3Bytes()), null);

            assertThat(saved.getKind()).isEqualTo(PieceAttachmentKind.AUDIO);
            verify(attachmentRepository, never()).countByPieceAndKind(piece, PieceAttachmentKind.DOCUMENT);
        }

        @Test
        @DisplayName("rejects a negative duration before touching the piece or the storage")
        void should_rejectNegativeDuration() {
            assertThatThrownBy(() -> sut.upload(7, 42, PieceAttachmentKind.AUDIO, file("c.mp3", "audio/mpeg", mp3Bytes()), -1L))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> {
                        ApiException api = (ApiException) e;
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(api.getCode()).isEqualTo(ErrorCodes.UPLOAD_INVALID_DURATION);
                    });
            verify(pieceService, never()).findInOrg(anyInt(), anyInt());
        }

        @Test
        @DisplayName("a zero duration is allowed (unknown or silent file)")
        void should_allowZeroDuration() {
            stubStorage();

            PieceAttachment saved = sut.upload(7, 42, PieceAttachmentKind.AUDIO, file("c.mp3", "audio/mpeg", mp3Bytes()), 0L);

            assertThat(saved.getDurationMs()).isZero();
        }

        @Test
        @DisplayName("ignores the duration for documents and images")
        void should_ignoreDurationForOtherKinds() {
            stubStorage();

            PieceAttachment doc = sut.upload(7, 42, PieceAttachmentKind.DOCUMENT, file("notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)), 5000L);

            assertThat(doc.getKind()).isEqualTo(PieceAttachmentKind.DOCUMENT);
            assertThat(doc.getDurationMs()).isNull();
        }

        @Test
        @DisplayName("the four-argument upload keeps working with no duration")
        void should_keepLegacyOverload() {
            stubStorage();

            PieceAttachment saved = sut.upload(7, 42, PieceAttachmentKind.AUDIO, file("c.mp3", "audio/mpeg", mp3Bytes()));

            assertThat(saved.getKind()).isEqualTo(PieceAttachmentKind.AUDIO);
            assertThat(saved.getDurationMs()).isNull();
        }

        @Test
        @DisplayName("never promotes an audio to cover")
        void should_notPromoteAudioToCover() {
            stubStorage();

            sut.upload(7, 42, PieceAttachmentKind.AUDIO, file("c.mp3", "audio/mpeg", mp3Bytes()), 1L);

            assertThat(piece.getCoverAttachment()).isNull();
            verify(pieceRepository, never()).save(any(Piece.class));
        }

        @Test
        @DisplayName("an MP3 uploaded as DOCUMENT is still a document")
        void should_keepDocumentKindForAudioBytes() {
            stubStorage();

            PieceAttachment doc = sut.upload(7, 42, PieceAttachmentKind.DOCUMENT, file("c.mp3", "audio/mpeg", mp3Bytes()), null);

            assertThat(doc.getKind()).isEqualTo(PieceAttachmentKind.DOCUMENT);
            assertThat(doc.getMimeType()).isEqualTo("audio/mpeg");
        }
    }

    @Nested
    @DisplayName("openContent")
    class OpenContent {

        private PieceAttachment stored() {
            return new PieceAttachment().setId(5).setKind(PieceAttachmentKind.AUDIO)
                    .setR2Key("org/7/piece/42/u-nature.mp3").setOriginalFilename("nature.mp3")
                    .setMimeType("audio/mpeg").setSizeBytes(3).setPiece(piece);
        }

        @Test
        @DisplayName("returns the attachment and the stream the storage opens for its key")
        void should_openStreamOfAttachment() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            PieceAttachment att = stored();
            when(attachmentRepository.findById(5)).thenReturn(Optional.of(att));
            InputStream bytes = new ByteArrayInputStream(new byte[]{1, 2, 3});
            when(r2Service.open("org/7/piece/42/u-nature.mp3")).thenReturn(bytes);

            AttachmentContent content = sut.openContent(7, 42, 5);

            assertThat(content.attachment()).isSameAs(att);
            assertThat(content.stream()).isSameAs(bytes);
        }

        @Test
        @DisplayName("404 when the attachment belongs to another piece")
        void should_rejectAttachmentOfAnotherPiece() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            PieceAttachment foreign = stored().setPiece(new Piece().setId(43));
            when(attachmentRepository.findById(5)).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> sut.openContent(7, 42, 5))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                    .isEqualTo(404);
            verify(r2Service, never()).open(anyString());
        }

        @Test
        @DisplayName("404 when the attachment does not exist")
        void should_rejectMissingAttachment() {
            when(pieceService.findInOrg(7, 42)).thenReturn(piece);
            when(attachmentRepository.findById(5)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.openContent(7, 42, 5)).isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("response DTO and headers")
    class Dto {

        @Test
        @DisplayName("carries the duration of an audio and null for the rest")
        void should_exposeDuration() {
            PieceAttachment audio = new PieceAttachment().setId(1).setKind(PieceAttachmentKind.AUDIO)
                    .setOriginalFilename("a.mp3").setMimeType("audio/mpeg").setSizeBytes(10).setDurationMs(1234L);
            PieceAttachment image = new PieceAttachment().setId(2).setKind(PieceAttachmentKind.IMAGE)
                    .setOriginalFilename("a.png").setMimeType("image/png").setSizeBytes(10);

            assertThat(PieceAttachmentResponseDto.from(audio).durationMs()).isEqualTo(1234L);
            assertThat(PieceAttachmentResponseDto.from(image).durationMs()).isNull();
        }

        @Test
        @DisplayName("inline disposition escapes quotes and encodes non-ASCII names")
        void should_buildInlineDisposition() {
            assertThat(PieceAttachmentService.inlineDispositionFor("cançó \"nit\".mp3"))
                    .isEqualTo("inline; filename=\"cançó _nit_.mp3\"; filename*=UTF-8''can%C3%A7%C3%B3%20%22nit%22.mp3");
            assertThat(PieceAttachmentService.inlineDispositionFor(null)).startsWith("inline; filename=\"file\"");
        }
    }

    @Nested
    @DisplayName("default limits")
    class Defaults {

        @Test
        @DisplayName("accept every format VLC plays on the Raspberry Pi")
        void should_listPlayerFormats() {
            assertThat(limits.getAllowedAudioMimes()).containsAll(Arrays.asList(
                    "audio/mpeg", "audio/vnd.wave", "audio/ogg", "application/ogg", "audio/x-flac", "audio/mp4"));
            assertThat(limits.getMaxAudioBytes()).isEqualTo(52_428_800L);
            assertThat(limits.getMaxAudiosPerPiece()).isEqualTo(50);
        }
    }
}
