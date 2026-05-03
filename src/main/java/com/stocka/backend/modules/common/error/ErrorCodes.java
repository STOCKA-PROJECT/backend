package com.stocka.backend.modules.common.error;

/**
 * Constantes para los codes estables del API. Agrupados por módulo.
 *
 * <p>El formato del code es {@code <modulo>.<evento>} en snake_case minúsculas.
 * Cada code DEBE tener traducción en {@code messages_es/ca/en.properties} bajo
 * la clave {@code errors.<code>}.
 *
 * <p>Los codes con prefijo {@code legacy.} los emite el handler global como
 * puente para los {@code ResponseStatusException} que aún no se han migrado.
 */
public final class ErrorCodes {

    private ErrorCodes() {
        // utility class
    }

    // ---- auth -----------------------------------------------------------
    public static final String AUTH_INVALID_CREDENTIALS = "auth.invalid_credentials";
    public static final String AUTH_EMAIL_NOT_VERIFIED = "auth.email_not_verified";
    public static final String AUTH_PASSWORDS_MISMATCH = "auth.passwords_mismatch";
    public static final String AUTH_VERIFICATION_TOKEN_INVALID = "auth.verification_token_invalid";
    public static final String AUTH_UNAUTHENTICATED = "auth.unauthenticated";
    public static final String AUTH_FORBIDDEN = "auth.forbidden";

    // ---- users ----------------------------------------------------------
    public static final String USERS_EMAIL_TAKEN = "users.email_taken";
    public static final String USERS_USERNAME_TAKEN = "users.username_taken";
    public static final String USERS_USERNAME_INVALID = "users.username_invalid";
    public static final String USERS_USERNAME_RESERVED = "users.username_reserved";

    // ---- pieces / piece types / locations -------------------------------
    public static final String PIECES_NOT_FOUND = "pieces.not_found";
    public static final String PIECE_TYPES_NOT_FOUND = "piece_types.not_found";
    public static final String PIECE_TYPES_ATTRIBUTE_INVALID = "piece_types.attribute_invalid";
    public static final String LOCATIONS_NOT_FOUND = "locations.not_found";

    // ---- organizations --------------------------------------------------
    public static final String ORGANIZATIONS_NOT_FOUND = "organizations.not_found";
    public static final String ORGANIZATIONS_INVITATION_LIMIT_REACHED = "organizations.invitation_limit_reached";
    public static final String ORGANIZATIONS_INVITATION_EXPIRED = "organizations.invitation_expired";

    // ---- storage / upload -----------------------------------------------
    public static final String STORAGE_R2_UNAVAILABLE = "storage.r2_unavailable";
    public static final String UPLOAD_TOO_LARGE = "upload.too_large";
    public static final String UPLOAD_INVALID_KIND = "upload.invalid_kind";

    // ---- generic --------------------------------------------------------
    public static final String VALIDATION_REQUIRED = "validation.required";
    public static final String VALIDATION_FAILED = "validation.failed";
    public static final String SERVER_INTERNAL_ERROR = "server.internal_error";
    public static final String REQUEST_MALFORMED_BODY = "request.malformed_body";
}
