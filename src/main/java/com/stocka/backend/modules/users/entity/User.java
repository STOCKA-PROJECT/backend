package com.stocka.backend.modules.users.entity;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.hibernate.annotations.SQLRestriction;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocka.backend.modules.roles.entity.Role;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
@SQLRestriction("deleted_at IS NULL")
public class User implements UserDetails {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private boolean emailVerified = false;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String email;

    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @ManyToOne
    @JoinColumn(name = "role_id", referencedColumnName = "id", nullable = false)
    private Role role;

    @JsonIgnore
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @JsonIgnore
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false, length = 4, columnDefinition = "VARCHAR(4) NOT NULL DEFAULT 'ES'")
    private Language language = Language.ES;

    @Column(name = "two_factor_enabled", nullable = false)
    private boolean twoFactorEnabled = false;

    /** AES-GCM encrypted TOTP secret. {@code null} until 2FA is confirmed. */
    @JsonIgnore
    @Column(name = "two_factor_secret", length = 255)
    private String twoFactorSecret;

    @JsonIgnore
    @Column(name = "two_factor_enabled_at")
    private LocalDateTime twoFactorEnabledAt;

    public Integer getId() {
        return id;
    }

    public User setId(Integer id) {
        this.id = id;
        return this;
    }

    public String getName() {
        return name;
    }

    public User setName(String name) {
        this.name = name;
        return this;
    }

    public String getLastName() {
        return lastName;
    }

    public User setLastName(String lastName) {
        this.lastName = lastName;
        return this;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public User setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
        return this;
    }

    @JsonProperty("username")
    public String getUsernameValue() {
        return username;
    }

    public User setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getEmail() {
        return email;
    }

    public User setEmail(String email) {
        this.email = email;
        return this;
    }

    @Override
    public String getPassword() {
        return password;
    }

    public User setPassword(String password) {
        this.password = password;
        return this;
    }

    public Role getRole() {
        return role;
    }

    public User setRole(Role role) {
        this.role = role;
        return this;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public User setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
        return this;
    }

    public LocalDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public User setPasswordChangedAt(LocalDateTime passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
        return this;
    }

    public Language getLanguage() {
        return language;
    }

    public User setLanguage(Language language) {
        this.language = language;
        return this;
    }

    public boolean isTwoFactorEnabled() {
        return twoFactorEnabled;
    }

    public User setTwoFactorEnabled(boolean twoFactorEnabled) {
        this.twoFactorEnabled = twoFactorEnabled;
        return this;
    }

    public String getTwoFactorSecret() {
        return twoFactorSecret;
    }

    public User setTwoFactorSecret(String twoFactorSecret) {
        this.twoFactorSecret = twoFactorSecret;
        return this;
    }

    public LocalDateTime getTwoFactorEnabledAt() {
        return twoFactorEnabledAt;
    }

    public User setTwoFactorEnabledAt(LocalDateTime twoFactorEnabledAt) {
        this.twoFactorEnabledAt = twoFactorEnabledAt;
        return this;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role.getName());
        return List.of(authority);
    }

    @Override
    @JsonIgnore
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
