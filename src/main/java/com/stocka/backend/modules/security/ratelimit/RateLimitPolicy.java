package com.stocka.backend.modules.security.ratelimit;

import java.time.Duration;

/**
 * Política de rate-limit identificada por nombre estable.
 *
 * @param name identificador de la política (p.ej. {@code "auth.login.ip"});
 *             se usa también en logs y métricas
 * @param capacity número máximo de tokens del bucket (= máximo de peticiones
 *                 que se pueden encolar de golpe)
 * @param refillTokens tokens que se reponen por cada {@code refillPeriod}
 * @param refillPeriod periodo de reposición
 */
public record RateLimitPolicy(String name, long capacity, long refillTokens, Duration refillPeriod) {

    public RateLimitPolicy {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (capacity <= 0 || refillTokens <= 0 || refillPeriod == null
                || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("capacity, refillTokens and refillPeriod must be positive");
        }
    }
}
