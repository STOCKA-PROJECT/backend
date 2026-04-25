package com.stocka.backend.modules.auth.dto;

public class RegisterUserDto {
    private String username;
    private String name;
    private String lastName;
    private String email;
    private String password;
    private String repeatPassword;
    private String language;

    public String getUsername() {
        return username;
    }

    public RegisterUserDto setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getName() {
        return name;
    }

    public RegisterUserDto setName(String name) {
        this.name = name;
        return this;
    }

    public String getLastName() {
        return lastName;
    }

    public RegisterUserDto setLastName(String lastName) {
        this.lastName = lastName;
        return this;
    }

    public String getEmail() {
        return email;
    }

    public RegisterUserDto setEmail(String email) {
        this.email = email;
        return this;
    }

    public String getPassword() {
        return password;
    }

    public RegisterUserDto setPassword(String password) {
        this.password = password;
        return this;
    }

    public String getRepeatPassword() {
        return repeatPassword;
    }

    public RegisterUserDto setRepeatPassword(String repeatPassword) {
        this.repeatPassword = repeatPassword;
        return this;
    }

    public String getLanguage() {
        return language;
    }

    public RegisterUserDto setLanguage(String language) {
        this.language = language;
        return this;
    }
}
