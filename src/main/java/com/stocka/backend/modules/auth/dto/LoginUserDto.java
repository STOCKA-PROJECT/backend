package com.stocka.backend.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class LoginUserDto {
    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;

    /**
     * Whether the resulting session should outlive the browser. Maps to the
     * {@code remember-ttl-days} configuration (30 days by default) instead of
     * the shorter {@code session-ttl-days} (7 days). Wiring for the checkbox
     * lands in Feature 5; the field is exposed already so the API contract is
     * stable.
     */
    private boolean rememberMe;

    public String getEmail() {
        return email;
    }

    public LoginUserDto setEmail(String email) {
        this.email = email;
        return this;
    }

    public String getPassword() {
        return password;
    }

    public LoginUserDto setPassword(String password) {
        this.password = password;
        return this;
    }

    public boolean isRememberMe() {
        return rememberMe;
    }

    public LoginUserDto setRememberMe(boolean rememberMe) {
        this.rememberMe = rememberMe;
        return this;
    }
}
