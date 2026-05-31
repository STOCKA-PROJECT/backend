package com.stocka.backend.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /auth/2fa/disable}. Requires both factors: the current
 * password (something you know) plus a valid TOTP / recovery code (something
 * you have). Without the dual factor an attacker with a leaked password could
 * turn off 2FA from a hijacked session.
 */
public class TwoFactorDisableRequestDto {

    @NotBlank
    private String currentPassword;

    @NotBlank
    private String code;

    public String getCurrentPassword() {
        return currentPassword;
    }

    public TwoFactorDisableRequestDto setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
        return this;
    }

    public String getCode() {
        return code;
    }

    public TwoFactorDisableRequestDto setCode(String code) {
        this.code = code;
        return this;
    }
}
