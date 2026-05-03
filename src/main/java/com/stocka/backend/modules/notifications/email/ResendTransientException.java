package com.stocka.backend.modules.notifications.email;

/**
 * Marker exception used to signal a transient Resend failure (HTTP 429/5xx or network error)
 * that should be retried by Spring Retry. Non-transient errors (4xx other than 429) do not
 * throw this exception and therefore are not retried.
 */
public class ResendTransientException extends RuntimeException {

    public ResendTransientException(String message, Throwable cause) {
        super(message, cause);
    }

    public ResendTransientException(String message) {
        super(message);
    }
}
