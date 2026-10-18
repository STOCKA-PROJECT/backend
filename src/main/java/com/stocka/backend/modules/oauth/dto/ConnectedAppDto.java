package com.stocka.backend.modules.oauth.dto;

import java.time.Instant;
import java.util.List;

/**
 * An OAuth client the user has authorized, as listed under {@code GET /users/me/connected-apps}.
 *
 * @param clientId      wire client id (random string for dynamically registered clients, the
 *                      metadata URL for Client ID Metadata Document clients)
 * @param name          display name
 * @param clientUri     home page, may be {@code null}
 * @param logoUri       logo, may be {@code null}
 * @param trusted       whether the client comes from a trusted metadata host
 * @param registeredVia {@code CIMD}, {@code DCR} or {@code MANUAL}
 * @param scopes        union of the granted scopes
 * @param grantedAt     first authorization
 * @param lastUsedAt    last token issuance (code exchange or refresh)
 * @param expiresAt     when the last token expires, may be {@code null}
 */
public record ConnectedAppDto(
        String clientId,
        String name,
        String clientUri,
        String logoUri,
        boolean trusted,
        String registeredVia,
        List<String> scopes,
        Instant grantedAt,
        Instant lastUsedAt,
        Instant expiresAt
) {
}
