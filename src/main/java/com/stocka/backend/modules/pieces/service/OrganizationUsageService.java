package com.stocka.backend.modules.pieces.service;

import org.springframework.stereotype.Service;

import com.stocka.backend.modules.organizations.dto.OrganizationUsageDto;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationQuotaProperties;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;

/**
 * Computes the per-organization quota usage snapshot exposed at
 * {@code GET /organizations/{orgId}/usage} so alerting systems and dashboards can monitor
 * consumption against the configured limits (issue #21).
 */
@Service
public class OrganizationUsageService {
    private final OrganizationService organizationService;
    private final PieceRepository pieceRepository;
    private final PieceAttachmentRepository attachmentRepository;
    private final OrganizationQuotaProperties quotas;

    public OrganizationUsageService(
            OrganizationService organizationService,
            PieceRepository pieceRepository,
            PieceAttachmentRepository attachmentRepository,
            OrganizationQuotaProperties quotas
    ) {
        this.organizationService = organizationService;
        this.pieceRepository = pieceRepository;
        this.attachmentRepository = attachmentRepository;
        this.quotas = quotas;
    }

    /**
     * Returns the current usage and configured limits for the given organization.
     *
     * @param orgId organization identifier
     * @return current pieces count, current attachment bytes and the configured maximums
     */
    public OrganizationUsageDto getUsage(Integer orgId) {
        Organization org = organizationService.findById(orgId);
        long pieces = pieceRepository.countByOrganization(org);
        long bytes = attachmentRepository.sumSizeBytesByOrganization(org);
        return new OrganizationUsageDto(
                org.getId(),
                pieces,
                quotas.getMaxPiecesPerOrg(),
                bytes,
                quotas.getMaxBytesPerOrg());
    }
}
