package com.stocka.backend.modules.auth.dto;

public class VerifyEmailRequestDto {
    private String token;

    public String getToken() {
        return token;
    }

    public VerifyEmailRequestDto setToken(String token) {
        this.token = token;
        return this;
    }
}
