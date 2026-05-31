package com.stocka.backend.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /auth/oauth/google/callback}. The frontend forwards the
 * {@code code} + {@code state} it received in the redirect URL.
 */
public class OAuthCallbackRequestDto {

    @NotBlank
    private String code;

    @NotBlank
    private String state;

    private boolean rememberMe;

    public String getCode() { return code; }
    public OAuthCallbackRequestDto setCode(String code) { this.code = code; return this; }

    public String getState() { return state; }
    public OAuthCallbackRequestDto setState(String state) { this.state = state; return this; }

    public boolean isRememberMe() { return rememberMe; }
    public OAuthCallbackRequestDto setRememberMe(boolean rememberMe) { this.rememberMe = rememberMe; return this; }
}
