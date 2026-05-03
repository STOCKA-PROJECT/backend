package com.stocka.backend.modules.auth.dto;

public class ResendVerificationRequestDto {
    private String email;

    public String getEmail() {
        return email;
    }

    public ResendVerificationRequestDto setEmail(String email) {
        this.email = email;
        return this;
    }
}
