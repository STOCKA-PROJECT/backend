package com.stocka.backend.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class VerifyEmailRequestDto {
    @NotBlank
    private String token;

    public String getToken() {
        return token;
    }

    public VerifyEmailRequestDto setToken(String token) {
        this.token = token;
        return this;
    }
}
