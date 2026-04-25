package com.stocka.backend.modules.auth.dto;

public class ForgotPasswordRequestDto {
    private String email;

    public String getEmail() {
        return email;
    }

    public ForgotPasswordRequestDto setEmail(String email) {
        this.email = email;
        return this;
    }
}
