package com.stocka.backend.modules.security.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de CORS. Se enlazan al prefijo {@code stocka.cors}.
 *
 * <p>Define la allowlist explícita de orígenes admitidos, los métodos y
 * cabeceras permitidos y si la respuesta puede incluir credenciales. La
 * allowlist se inyecta desde {@code CORS_ALLOWED_ORIGINS} (lista separada
 * por comas) vía {@code application.properties}.
 */
@ConfigurationProperties(prefix = "stocka.cors")
public class CorsProperties {

    /**
     * Orígenes admitidos. Para evitar configuraciones peligrosas no se acepta
     * el comodín {@code *}; añade explícitamente cada dominio. Vacío por
     * defecto: el valor real se inyecta vía {@code CORS_ALLOWED_ORIGINS}.
     */
    private List<String> allowedOrigins = List.of();

    /** Métodos HTTP permitidos por CORS. */
    private List<String> allowedMethods = List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS");

    /** Cabeceras de petición que el navegador puede enviar. */
    private List<String> allowedHeaders = List.of(
            "Authorization", "Content-Type", "Accept", "Accept-Language", "If-Match");

    /**
     * Indica si las respuestas pueden exponer credenciales (cookies). Debe
     * mantenerse en {@code false} mientras se use autenticación Bearer; sólo
     * conviene activarlo si en el futuro se introducen cookies HttpOnly.
     */
    private boolean allowCredentials = false;

    /** Edad máxima (segundos) de la cache de preflight en el navegador. */
    private long maxAgeSeconds = 3600;

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }

    public List<String> getAllowedMethods() {
        return allowedMethods;
    }

    public void setAllowedMethods(List<String> allowedMethods) {
        this.allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
    }

    public List<String> getAllowedHeaders() {
        return allowedHeaders;
    }

    public void setAllowedHeaders(List<String> allowedHeaders) {
        this.allowedHeaders = allowedHeaders == null ? List.of() : List.copyOf(allowedHeaders);
    }

    public boolean isAllowCredentials() {
        return allowCredentials;
    }

    public void setAllowCredentials(boolean allowCredentials) {
        this.allowCredentials = allowCredentials;
    }

    public long getMaxAgeSeconds() {
        return maxAgeSeconds;
    }

    public void setMaxAgeSeconds(long maxAgeSeconds) {
        this.maxAgeSeconds = maxAgeSeconds;
    }
}
