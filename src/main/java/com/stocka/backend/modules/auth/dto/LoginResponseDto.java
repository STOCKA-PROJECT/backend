package com.stocka.backend.modules.auth.dto;

import com.stocka.backend.modules.users.dto.UserResponseDto;

/**
 * Response body for {@code POST /auth/login} and {@code POST /auth/refresh}.
 *
 * <p>The refresh token is <em>not</em> in this payload — it travels exclusively
 * via the {@code stocka_refresh} httpOnly cookie set on the same response.
 */
public class LoginResponseDto {

    private String accessToken;
    private long expiresIn;
    private UserResponseDto user;

    public String getAccessToken() {
        return accessToken;
    }

    public LoginResponseDto setAccessToken(String accessToken) {
        this.accessToken = accessToken;
        return this;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public LoginResponseDto setExpiresIn(long expiresIn) {
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
}
