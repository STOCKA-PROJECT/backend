package com.stocka.backend.modules.auth.dto;

import com.stocka.backend.modules.users.entity.User;

public class LoginResponseDto {
    private String token;
    private long expiresIn;
    private User user;

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

    public User getUser() {
        return user;
    }

    public LoginResponseDto setUser(User user) {
        this.user = user;
        return this;
    }
}
