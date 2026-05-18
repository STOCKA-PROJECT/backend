package com.stocka.backend.modules.organizations.dto;

/**
 * Response of {@code GET /organizations/by-slug/{slug}}. When {@code historical} is
 * {@code true}, the frontend should replace the URL it loaded with {@code currentSlug}
 * (effectively a client-side 301 redirect for deep links generated before a slug rename).
 *
 * @param org the resolved organization with the caller's current role
 * @param historical whether the input slug was an old value
 * @param currentSlug the up-to-date slug; equals the input when {@code historical} is
 *     {@code false}
 */
public record OrganizationLookupResponseDto(
        OrganizationResponseDto org,
        boolean historical,
        String currentSlug
) {
}
