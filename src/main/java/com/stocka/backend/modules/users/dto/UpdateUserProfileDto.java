package com.stocka.backend.modules.users.dto;

public class UpdateUserProfileDto {
    private String name;
    private String lastName;
    private String email;
    private String username;
    private String language;

    public String getName() {
        return name;
    }

    public UpdateUserProfileDto setName(String name) {
        this.name = name;
        return this;
    }

    public String getLastName() {
        return lastName;
    }

    public UpdateUserProfileDto setLastName(String lastName) {
        this.lastName = lastName;
        return this;
    }

    public String getEmail() {
        return email;
    }

    public UpdateUserProfileDto setEmail(String email) {
        this.email = email;
        return this;
    }

    public String getUsername() {
        return username;
    }

    public UpdateUserProfileDto setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getLanguage() {
        return language;
    }

    public UpdateUserProfileDto setLanguage(String language) {
        this.language = language;
        return this;
    }
}
