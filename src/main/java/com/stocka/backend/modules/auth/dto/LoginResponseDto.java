package com.stocka.backend.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stocka.backend.modules.users.dto.UserResponseDto;

/**
 * Response body for {@code POST /auth/login}, {@code POST /auth/login/2fa} and
 * {@code POST /auth/refresh}.
 *
 * <p>The refresh token never lives in this payload — it travels exclusively
 * via the {@code stocka_refresh} httpOnly cookie set on the same response.
 *
 * <p>When 2FA is required, the response is the polymorphic
 * {@code { requires2fa: true, mfaToken: "..." }} variant; {@code accessToken}
 * and {@code user} are absent. The frontend dispatches on {@code requires2fa}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginResponseDto {

    private String accessToken;
    private Long expiresIn;
    private UserResponseDto user;
    private Boolean requires2fa;
    private String mfaToken;

    public String getAccessToken() {
        return accessToken;
    }

    public LoginResponseDto setAccessToken(String accessToken) {
        this.accessToken = accessToken;
        return this;
    }

    public Long getExpiresIn() {
        return expiresIn;
    }

    public LoginResponseDto setExpiresIn(Long expiresIn) {
        this.expiresIn = expiresIn;
        return this;
    }

    public UserResponseDto getUser() {
        return user;
    }

    public LoginResponseDto setUser(UserResponseDto user) {
        this.user = user;
        return this;
    }

    public Boolean getRequires2fa() {
        return requires2fa;
    }

    public LoginResponseDto setRequires2fa(Boolean requires2fa) {
        this.requires2fa = requires2fa;
        return this;
    }

    public String getMfaToken() {
        return mfaToken;
    }

    public LoginResponseDto setMfaToken(String mfaToken) {
        this.mfaToken = mfaToken;
        return this;
    }
}
