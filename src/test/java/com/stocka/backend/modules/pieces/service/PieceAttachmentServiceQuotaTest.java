package com.stocka.backend.modules.pieces.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationQuotaProperties;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.storage.R2Properties;
import com.stocka.backend.modules.storage.R2Service;

@ExtendWith(MockitoExtension.class)
@DisplayName("PieceAttachmentService — per-organization storage quota")
class PieceAttachmentServiceQuotaTest {

    @Mock PieceAttachmentRepository attachmentRepository;
    @Mock PieceRepository pieceRepository;
    @Mock PieceService pieceService;
    @Mock PieceHistoryService historyService;
    @Mock R2Service r2Service;
    @Mock R2Properties r2Properties;

    private PieceAttachmentService sut;
    private Piece piece;
    private OrganizationQuotaProperties quotas;

    @BeforeEach
    void setUp() {
        PieceAttachmentProperties limits = new PieceAttachmentProperties();
        limits.setAllowedImageMimes(Set.of("image/png"));
        quotas = new OrganizationQuotaProperties();
        sut = new PieceAttachmentService(
                attachmentRepository, pieceRepository, pieceService,
                historyService, r2Service, r2Properties, limits, quotas);
        piece = new Piece().setId(42).setOrganization(new Organization().setId(7));
    }

    @Test
    @DisplayName("rejects upload with 403 organizations.quota_exceeded when current+size exceeds maxBytesPerOrg")
    void should_throw403_when_bytesPerOrgQuotaExceeded() {
        quotas.setMaxBytesPerOrg(1_000L);
        byte[] body = new byte[200];
        MockMultipartFile pdf = new MockMultipartFile("file", "big.pdf", "application/pdf", body);
        when(pieceService.findInOrg(7, 42)).thenReturn(piece);
        when(attachmentRepository.sumSizeBytesByOrganization(piece.getOrganization()))
                .thenReturn(900L);

        ApiException ex = assertThrows(ApiException.class,
                () -> sut.upload(7, 42, PieceAttachmentKind.DOCUMENT, pdf));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getCode()).isEqualTo(ErrorCodes.ORGANIZATIONS_QUOTA_EXCEEDED);
        assertThat(ex.getParams()).containsEntry("limit", "max_bytes_per_org");
        verify(attachmentRepository, never()).save(any(PieceAttachment.class));
    }

    @Test
    @DisplayName("rejects when adding a single huge file from zero current usage")
    void should_rejectFirstHugeUpload_when_singleFileExceedsCap() {
        quotas.setMaxBytesPerOrg(100L);
        byte[] body = new byte[200];
        MockMultipartFile pdf = new MockMultipartFile("file", "huge.pdf", "application/pdf", body);
        when(pieceService.findInOrg(7, 42)).thenReturn(piece);
        when(attachmentRepository.sumSizeBytesByOrganization(piece.getOrganization()))
                .thenReturn(0L);

        ApiException ex = assertThrows(ApiException.class,
                () -> sut.upload(7, 42, PieceAttachmentKind.DOCUMENT, pdf));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.ORGANIZATIONS_QUOTA_EXCEEDED);
        assertThat(ex.getParams())
                .containsEntry("max", 100L)
                .containsEntry("current", 0L)
                .containsEntry("requested", 200L);
        verify(attachmentRepository, never()).save(any(PieceAttachment.class));
    }
}
