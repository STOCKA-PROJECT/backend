package com.stocka.backend.modules.auth.dto;

import com.stocka.backend.modules.users.dto.UserResponseDto;

public class LoginResponseDto {
    private String token;
    private long expiresIn;
    private UserResponseDto user;

    public String getToken() {
        return token;
    }

    public LoginResponseDto setToken(String token) {
        this.token = token;
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
