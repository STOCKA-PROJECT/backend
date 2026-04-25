package com.stocka.backend.modules.auth.dto;

public class ResetPasswordRequestDto {
    private String token;
    private String newPassword;
    private String repeatPassword;

    public String getToken() {
        return token;
    }

    public ResetPasswordRequestDto setToken(String token) {
        this.token = token;
        return this;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public ResetPasswordRequestDto setNewPassword(String newPassword) {
        this.newPassword = newPassword;
        return this;
    }

    public String getRepeatPassword() {
        return repeatPassword;
    }

    public ResetPasswordRequestDto setRepeatPassword(String repeatPassword) {
        this.repeatPassword = repeatPassword;
        return this;
    }
}
