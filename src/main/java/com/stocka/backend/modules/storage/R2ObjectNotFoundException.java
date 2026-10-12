package com.stocka.backend.modules.storage;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by {@link R2Service#open(String)} when the bucket (or the local fallback) has no object
 * under the requested key: the metadata row exists but its bytes are gone. Maps to HTTP 404.
 */
@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "Archivo no encontrado")
public class R2ObjectNotFoundException extends RuntimeException {
    public R2ObjectNotFoundException(String message) {
        super(message);
    }

    public R2ObjectNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
