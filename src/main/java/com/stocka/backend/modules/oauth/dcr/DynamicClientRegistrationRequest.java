package com.stocka.backend.modules.oauth.dcr;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /oauth2/register} (RFC 7591 client metadata). Unknown members are ignored,
 * as the RFC requires.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DynamicClientRegistrationRequest(
        @JsonProperty("client_name") String clientName,
        @JsonProperty("redirect_uris") List<String> redirectUris,
        @JsonProperty("grant_types") List<String> grantTypes,
        @JsonProperty("response_types") List<String> responseTypes,
        @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
        @JsonProperty("application_type") String applicationType,
        @JsonProperty("client_uri") String clientUri,
        @JsonProperty("logo_uri") String logoUri,
        @JsonProperty("scope") String scope
) {
}
