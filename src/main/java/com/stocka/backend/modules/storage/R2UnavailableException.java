package com.stocka.backend.modules.storage;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by {@code R2Service} implementations when the underlying object store is misconfigured
 * or temporarily unreachable. Maps to HTTP 503 in REST responses.
 */
@ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE, reason = "Almacenamiento no disponible")
public class R2UnavailableException extends RuntimeException {
    public R2UnavailableException(String message) {
        super(message);
    }

    public R2UnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
