package com.stocka.backend.modules.auth.dto;

/**
 * Response of {@code GET /auth/oauth/google/authorize}. The frontend redirects
 * the browser to {@code authorizationUrl} (which already contains the state),
 * and the corresponding state cookie travels back as a {@code Set-Cookie} on
 * this response.
 */
public record OAuthAuthorizeResponseDto(String authorizationUrl) {}
