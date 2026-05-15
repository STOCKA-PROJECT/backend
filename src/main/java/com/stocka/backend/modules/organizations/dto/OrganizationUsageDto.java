package com.stocka.backend.modules.organizations.dto;

/**
 * Snapshot of an organization's quota usage. Exposed so OWNER/MANAGER can monitor consumption
 * and alerting systems can scrape the values per organization (issue #21).
 *
 * @param organizationId  identifier of the organization
 * @param pieces          current count of (non-soft-deleted) pieces
 * @param maxPieces       configured maximum number of pieces per org
 * @param bytes           current sum of {@code size_bytes} across non-soft-deleted attachments
 * @param maxBytes        configured maximum total bytes per org
 */
public record OrganizationUsageDto(
        Integer organizationId,
        long pieces,
        long maxPieces,
        long bytes,
        long maxBytes
) {
}
