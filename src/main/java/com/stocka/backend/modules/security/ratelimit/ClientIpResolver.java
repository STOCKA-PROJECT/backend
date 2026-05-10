package com.stocka.backend.modules.security.ratelimit;

import java.util.Set;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resuelve la IP del cliente respetando {@code X-Forwarded-For} solo cuando la
 * petición llega desde un reverse-proxy explícitamente declarado de confianza
 * en {@link RateLimitProperties#getTrustedProxies()}.
 *
 * <p>Por defecto, sin proxies configurados, devuelve
 * {@link HttpServletRequest#getRemoteAddr()} para evitar que un atacante pueda
 * forjar la IP enviando esa cabecera.
 */
@Component
public class ClientIpResolver {

    private final Set<String> trustedProxies;

    public ClientIpResolver(RateLimitProperties properties) {
        this.trustedProxies = Set.copyOf(properties.getTrustedProxies());
    }

    /**
     * Devuelve la mejor estimación de la IP del cliente para esta petición.
     *
     * @param request petición HTTP
     * @return IP del cliente; nunca {@code null}
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!trustedProxies.isEmpty() && trustedProxies.contains(remoteAddr)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
                return first.trim();
            }
        }
        return remoteAddr == null ? "_unknown_" : remoteAddr;
    }
}
