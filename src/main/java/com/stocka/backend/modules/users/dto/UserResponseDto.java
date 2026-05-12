package com.stocka.backend.modules.users.dto;

import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;

/**
 * API-facing representation of a {@link User}. Exposes only the safe subset of
 * fields and never leaks credentials, soft-delete metadata or Spring Security
 * authorities (which could otherwise reveal {@code ROLE_ADMIN}).
 */
public class UserResponseDto {
    private Integer id;
    private String username;
    private String email;
    private String name;
    private String lastName;
    private Language language;
    private RoleEnum role;
    private boolean emailVerified;

    /**
     * Builds a response DTO from a {@link User} entity.
     *
     * @param user source entity; must not be {@code null}
     * @return a populated {@link UserResponseDto}
     */
    public static UserResponseDto from(User user) {
        UserResponseDto dto = new UserResponseDto();
        dto.id = user.getId();
        dto.username = user.getUsernameValue();
        dto.email = user.getEmail();
        dto.name = user.getName();
        dto.lastName = user.getLastName();
        dto.language = user.getLanguage();
        dto.role = user.getRole() != null ? user.getRole().getName() : null;
        dto.emailVerified = user.isEmailVerified();
        return dto;
    }

    public Integer getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getLastName() {
        return lastName;
    }

    public Language getLanguage() {
        return language;
    }

    public RoleEnum getRole() {
        return role;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }
}
