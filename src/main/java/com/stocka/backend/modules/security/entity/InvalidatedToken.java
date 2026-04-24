package com.stocka.backend.modules.security.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "invalidated_tokens")
public class InvalidatedToken {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @Column(nullable = false, unique = true, length = 2048)
    private String token;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public Integer getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public InvalidatedToken setToken(String token) {
        this.token = token;
        return this;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public InvalidatedToken setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
        return this;
    }
}
