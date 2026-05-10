package com.stocka.backend.modules.security.ratelimit;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de rate-limit. Se enlazan al prefijo {@code stocka.rate-limit}.
 *
 * <p>{@code trustedProxies} limita la confianza en {@code X-Forwarded-For} a
 * las IPs declaradas explícitamente. Si está vacío, siempre se usa
 * {@link jakarta.servlet.ServletRequest#getRemoteAddr()} como clave.
 */
@ConfigurationProperties(prefix = "stocka.rate-limit")
public class RateLimitProperties {

    /**
     * Activa o desactiva el filtro de rate-limit (por defecto activo).
     * Útil para desactivarlo en tests.
     */
    private boolean enabled = true;

    /**
     * Lista de IPs (o rangos exactos) consideradas reverse-proxies de confianza.
     * Si la petición llega desde una de estas IPs, se honra la primera entrada
     * de {@code X-Forwarded-For} como IP del cliente.
     */
    private List<String> trustedProxies = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? new ArrayList<>() : trustedProxies;
    }
}
