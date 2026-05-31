package com.stocka.backend.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /auth/login/2fa}. Carries the intermediate token
 * received from the first step and the user-typed code (either a TOTP code
 * or a recovery code).
 */
public class TwoFactorChallengeRequestDto {

    @NotBlank
    private String mfaToken;

    @NotBlank
    private String code;

    private boolean rememberMe;

    public String getMfaToken() {
        return mfaToken;
    }

    public TwoFactorChallengeRequestDto setMfaToken(String mfaToken) {
        this.mfaToken = mfaToken;
        return this;
    }

    public String getCode() {
        return code;
    }

    public TwoFactorChallengeRequestDto setCode(String code) {
        this.code = code;
        return this;
    }

    public boolean isRememberMe() {
        return rememberMe;
    }

    public TwoFactorChallengeRequestDto setRememberMe(boolean rememberMe) {
        this.rememberMe = rememberMe;
        return this;
    }
}
