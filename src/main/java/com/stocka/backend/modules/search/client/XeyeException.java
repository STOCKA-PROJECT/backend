package com.stocka.backend.modules.search.client;

/**
 * Failure of a call to the XEYE API.
 *
 * <p>{@link #status()} is the HTTP status (0 when the service could not be reached) and
 * {@link #code()} the stable machine-readable code of the XEYE error envelope
 * ({@code LIST_NOT_FOUND}, {@code CONFLICT}, {@code RATE_LIMITED}...), or {@code null} when the
 * body carried none.
 */
public class XeyeException extends RuntimeException {

    private final int status;
    private final String code;

    public XeyeException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public XeyeException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /** @return HTTP status of the failed call, or 0 when no response was received */
    public int status() {
        return status;
    }

    /** @return the XEYE error code, or {@code null} when the response had no envelope */
    public String code() {
        return code;
    }

    /** @return {@code true} for a 404 (unknown list or element) */
    public boolean isNotFound() {
        return status == 404;
    }

    /** @return {@code true} for a 409 (a training is already queued or running) */
    public boolean isConflict() {
        return status == 409;
    }

    /** @return {@code true} when the same call may succeed later (unreachable, 429 or 5xx) */
    public boolean isRetryable() {
        return status == 0 || status == 429 || status >= 500;
    }
}
