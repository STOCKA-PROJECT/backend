package com.stocka.backend.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class TwoFactorConfirmRequestDto {

    @NotBlank
    private String setupToken;

    @NotBlank
    private String code;

    public String getSetupToken() {
        return setupToken;
    }

    public TwoFactorConfirmRequestDto setSetupToken(String setupToken) {
        this.setupToken = setupToken;
        return this;
    }

    public String getCode() {
        return code;
    }

    public TwoFactorConfirmRequestDto setCode(String code) {
        this.code = code;
        return this;
    }
}
