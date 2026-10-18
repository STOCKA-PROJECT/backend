package com.stocka.backend.modules.oauth.client;

import java.util.List;

/**
 * The parts of an OAuth Client ID Metadata Document that the authorization server uses.
 *
 * @param clientId     the document URL (must equal the URL it was fetched from)
 * @param clientName   display name
 * @param clientUri    home page of the client, may be {@code null}
 * @param logoUri      logo, may be {@code null}
 * @param redirectUris allowed redirect URIs (never empty)
 */
public record ClientMetadataDocument(
        String clientId,
        String clientName,
        String clientUri,
        String logoUri,
        List<String> redirectUris
) {
}
