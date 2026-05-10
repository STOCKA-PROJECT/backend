package com.stocka.backend.modules.security.ratelimit;

import java.util.Map;

import org.springframework.http.HttpStatus;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;

/**
 * Se lanza cuando se supera el rate-limit configurado para un endpoint.
 *
 * <p>{@link com.stocka.backend.modules.common.error.GlobalExceptionHandler} la
 * traduce a una respuesta {@code 429 Too Many Requests} y añade la cabecera
 * {@code Retry-After} con el valor de {@link #getRetryAfterSeconds()}.
 */
public class RateLimitedException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitedException(String policyName, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.REQUEST_RATE_LIMITED, Map.of(
                "policy", policyName,
                "retryAfter", retryAfterSeconds
        ));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * @return segundos que el cliente debe esperar antes de reintentar
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
