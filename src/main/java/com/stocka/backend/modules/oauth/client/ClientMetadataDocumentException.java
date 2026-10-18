package com.stocka.backend.modules.oauth.client;

/**
 * A Client ID Metadata Document could not be fetched or is not acceptable.
 */
public class ClientMetadataDocumentException extends RuntimeException {

    /** Why the document was rejected. */
    public enum Reason { UNREACHABLE, INVALID }

    private final Reason reason;

    public ClientMetadataDocumentException(Reason reason, String message) {
        this(reason, message, null);
    }

    public ClientMetadataDocumentException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
