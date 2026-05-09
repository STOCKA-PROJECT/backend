package com.stocka.backend.modules.auth.dto;

import jakarta.validation.constraints.Email;

public class ResendVerificationRequestDto {
    // Anti-enumeration: a missing or empty email is intentionally accepted and
    // handled silently by the service, so we only validate the format when one
    // is provided.
    @Email
    private String email;

    public String getEmail() {
        return email;
    }

    public ResendVerificationRequestDto setEmail(String email) {
        this.email = email;
        return this;
    }
}
