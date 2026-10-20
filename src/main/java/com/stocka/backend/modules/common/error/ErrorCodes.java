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
    public static final String AUTH_CURRENT_PASSWORD_INVALID = "auth.current_password_invalid";
    public static final String AUTH_PASSWORD_TOO_SHORT = "auth.password_too_short";
    public static final String AUTH_NEW_PASSWORD_SAME_AS_CURRENT = "auth.new_password_same_as_current";
    public static final String AUTH_REFRESH_TOKEN_MISSING = "auth.refresh_token_missing";
    public static final String AUTH_REFRESH_TOKEN_INVALID = "auth.refresh_token_invalid";
    public static final String AUTH_REFRESH_TOKEN_EXPIRED = "auth.refresh_token_expired";
    public static final String AUTH_REFRESH_TOKEN_REUSED = "auth.refresh_token_reused";
    public static final String AUTH_TOKEN_EXPIRED = "auth.token_expired";
    public static final String AUTH_2FA_INVALID_CODE = "auth.2fa_invalid_code";
    public static final String AUTH_2FA_SETUP_TOKEN_INVALID = "auth.2fa_setup_token_invalid";
    public static final String AUTH_2FA_MFA_TOKEN_EXPIRED = "auth.2fa_mfa_token_expired";
    public static final String AUTH_2FA_ALREADY_ENABLED = "auth.2fa_already_enabled";
    public static final String AUTH_2FA_NOT_ENABLED = "auth.2fa_not_enabled";
    public static final String AUTH_2FA_REQUIRED = "auth.2fa_required";
    public static final String AUTH_HANDOFF_TOKEN_INVALID = "auth.handoff_token_invalid";
    public static final String OAUTH_NOT_CONFIGURED = "auth.oauth_not_configured";
    public static final String OAUTH_STATE_INVALID = "auth.oauth_state_invalid";
    public static final String OAUTH_PROVIDER_ERROR = "auth.oauth_provider_error";
    public static final String OAUTH_LINK_NOT_FOUND = "auth.oauth_link_not_found";

    // ---- users ----------------------------------------------------------
    public static final String USERS_EMAIL_TAKEN = "users.email_taken";
    public static final String USERS_USERNAME_TAKEN = "users.username_taken";
    public static final String USERS_USERNAME_INVALID = "users.username_invalid";
    public static final String USERS_USERNAME_RESERVED = "users.username_reserved";

    // ---- sessions / devices ---------------------------------------------
    public static final String SESSIONS_NOT_FOUND = "sessions.not_found";
    public static final String SESSIONS_NAME_INVALID = "sessions.name_invalid";

    // ---- pieces / piece types / locations -------------------------------
    public static final String PIECES_NOT_FOUND = "pieces.not_found";
    public static final String PIECES_OWNER_CONFLICT = "pieces.owner_conflict";
    public static final String PIECES_IMPORT_INVALID_FILE = "pieces.import.invalid_file";
    public static final String PIECES_IMPORT_TOO_MANY_ROWS = "pieces.import.too_many_rows";
    public static final String PIECES_IMPORT_HAS_BLOCKING_ERRORS = "pieces.import.has_blocking_errors";
    public static final String PIECES_EXPORT_TOO_MANY_ROWS = "pieces.export.too_many_rows";
    public static final String PIECES_FILTER_INVALID = "pieces.filter.invalid";
    public static final String PIECE_TYPES_NOT_FOUND = "piece_types.not_found";
    public static final String PIECE_TYPES_ATTRIBUTE_INVALID = "piece_types.attribute_invalid";
    public static final String PIECE_TYPES_NAME_CONFLICT = "piece_types.name_conflict";
    public static final String PIECE_TYPES_ATTRIBUTE_NAME_CONFLICT = "piece_types.attribute_name_conflict";
    public static final String PIECE_TYPES_KIND_INVALID = "piece_types.kind_invalid";
    public static final String PIECE_TYPES_KIND_CHANGE_INVALID = "piece_types.kind_change_invalid";
    public static final String PIECE_TYPES_KIND_IN_USE = "piece_types.kind_in_use";
    public static final String PIECE_TYPES_ACTION_NOT_IN_CATALOG = "piece_types.action_not_in_catalog";
    public static final String PIECE_TYPES_ACTION_PARAM_UNKNOWN = "piece_types.action_param_unknown";
    public static final String PIECE_TYPES_ACTION_PARAM_MISSING = "piece_types.action_param_missing";
    public static final String PIECE_TYPES_ACTION_PARAM_TYPE_MISMATCH = "piece_types.action_param_type_mismatch";
    public static final String PIECE_TYPES_ACTION_PARAM_OPTIONS_INVALID = "piece_types.action_param_options_invalid";
    public static final String PIECE_TYPES_ACTION_PARAM_STATIC_INVALID = "piece_types.action_param_static_invalid";
    public static final String PIECE_TYPES_ACTION_DURATION_PARAM_MISMATCH = "piece_types.action_duration_param_mismatch";
    public static final String PIECE_TYPES_ACTION_NAME_INVALID = "piece_types.action_name_invalid";
    public static final String LOCATIONS_NOT_FOUND = "locations.not_found";

    // ---- timelines ------------------------------------------------------
    public static final String TIMELINES_NOT_FOUND = "timelines.not_found";
    public static final String TIMELINES_NAME_CONFLICT = "timelines.name_conflict";
    public static final String TIMELINE_SCENE_DOCUMENT_INVALID = "timeline_scenes.document_invalid";
    public static final String TIMELINE_SCENE_INVALID_REFERENCE = "timeline_scenes.invalid_reference";
    public static final String TIMELINE_SCENE_VERSION_CONFLICT = "timeline_scenes.version_conflict";
    public static final String TIMELINE_SCENE_VERSION_REQUIRED = "timeline_scenes.version_required";
    public static final String TIMELINE_SCENE_SCHEMA_UNSUPPORTED = "timeline_scenes.schema_unsupported";
    public static final String TIMELINE_SCENE_OP_INVALID = "timeline_scenes.op_invalid";
    public static final String TIMELINE_SHOWS_SCENE_NOT_FOUND = "timeline_shows.scene_not_found";
    public static final String TIMELINE_SHOWS_INVALID = "timeline_shows.invalid";
    public static final String TIMELINE_SHOWS_ASSET_NOT_FOUND = "timeline_shows.asset_not_found";

    // ---- organizations --------------------------------------------------
    public static final String ORGANIZATIONS_NOT_FOUND = "organizations.not_found";
    public static final String ORGANIZATIONS_INVITATION_LIMIT_REACHED = "organizations.invitation_limit_reached";
    public static final String ORGANIZATIONS_INVITATION_EXPIRED = "organizations.invitation_expired";
    public static final String ORGANIZATIONS_INVITATION_ALREADY_PROCESSED = "organizations.invitation_already_processed";
    public static final String ORGANIZATIONS_QUOTA_EXCEEDED = "organizations.quota_exceeded";

    // ---- ports ----------------------------------------------------------
    public static final String PORTS_NOT_FOUND = "ports.not_found";
    public static final String PORTS_NAME_REQUIRED = "ports.name_required";
    public static final String PORTS_NAME_CONFLICT = "ports.name_conflict";
    public static final String PORTS_PIECE_TYPE_REQUIRED = "ports.piece_type_required";
    public static final String PORTS_PIN_INVALID = "ports.pin_invalid";
    public static final String PORTS_PIN_CONFLICT = "ports.pin_conflict";
    public static final String PORTS_KIND_REQUIRED = "ports.kind_required";
    public static final String PORTS_KIND_INVALID = "ports.kind_invalid";
    public static final String PORTS_KIND_IMMUTABLE = "ports.kind_immutable";
    public static final String PORTS_PIN_NOT_ALLOWED = "ports.pin_not_allowed";
    public static final String PORTS_PIN_RESERVED = "ports.pin_reserved";
    public static final String PORTS_PIECE_TYPE_KIND_MISMATCH = "ports.piece_type_kind_mismatch";
    public static final String PORTS_CONFIG_UNKNOWN_KEY = "ports.config_unknown_key";
    public static final String PORTS_CONFIG_MISSING_KEY = "ports.config_missing_key";
    public static final String PORTS_CONFIG_TYPE_MISMATCH = "ports.config_type_mismatch";
    public static final String PORTS_CONFIG_OUT_OF_RANGE = "ports.config_out_of_range";
    public static final String PORTS_MOTOR_PINS_NOT_DISTINCT = "ports.motor_pins_not_distinct";
    public static final String PORTS_STRIP_PIN_UNSUPPORTED = "ports.strip_pin_unsupported";
    public static final String PORTS_STRIP_CHANNEL_MISMATCH = "ports.strip_channel_mismatch";
    public static final String PORTS_SEGMENT_STRIP_NOT_FOUND = "ports.segment_strip_not_found";
    public static final String PORTS_SEGMENT_OUT_OF_RANGE = "ports.segment_out_of_range";
    public static final String PORTS_STRIP_SHRINK_BREAKS_SEGMENT = "ports.strip_shrink_breaks_segment";
    public static final String PORTS_STRIP_IN_USE = "ports.strip_in_use";
    public static final String PORTS_RELAY_PIN_OR_EXPANDER = "ports.relay_pin_or_expander";
    public static final String PORTS_RELAY_CHANNEL_REQUIRED = "ports.relay_channel_required";
    public static final String PORTS_RELAY_CHANNEL_WITHOUT_EXPANDER = "ports.relay_channel_without_expander";
    public static final String PORTS_RELAY_ACTIVE_LOW_ON_EXPANDER = "ports.relay_active_low_on_expander";
    public static final String PORTS_EXPANDER_NOT_FOUND = "ports.expander_not_found";
    public static final String PORTS_EXPANDER_CHANNEL_CONFLICT = "ports.expander_channel_conflict";
    public static final String PORTS_EXPANDER_ADDRESS_CONFLICT = "ports.expander_address_conflict";
    public static final String PORTS_EXPANDER_IN_USE = "ports.expander_in_use";
    public static final String PORTS_I2C_PIN_RESERVED = "ports.i2c_pin_reserved";
    public static final String PORTS_DMX_ADDRESS_RANGE = "ports.dmx_address_range";
    public static final String PORTS_DMX_ADDRESS_CONFLICT = "ports.dmx_address_conflict";

    // ---- devices (belén players + heartbeat) -----------------------------
    public static final String DEVICES_NOT_FOUND = "devices.not_found";
    public static final String DEVICES_NAME_REQUIRED = "devices.name_required";
    public static final String DEVICES_NAME_TOO_LONG = "devices.name_too_long";
    public static final String DEVICES_NAME_TAKEN = "devices.name_taken";
    public static final String DEVICES_TOKEN_INVALID = "devices.token_invalid";

    // ---- contacts --------------------------------------------------------
    public static final String CONTACTS_NOT_FOUND = "contacts.not_found";
    public static final String CONTACTS_NAME_REQUIRED = "contacts.name_required";
    public static final String CONTACTS_EMAIL_CONFLICT = "contacts.email_conflict";
    public static final String CONTACTS_OWNS_PIECES = "contacts.owns_pieces";
    public static final String CONTACTS_ALREADY_LINKED = "contacts.already_linked";
    public static final String CONTACTS_USER_ALREADY_LINKED = "contacts.user_already_linked";
    public static final String CONTACTS_LINK_USER_NOT_MEMBER = "contacts.link_user_not_member";
    public static final String CONTACTS_LINK_SPECTATOR_CANNOT_OWN = "contacts.link_spectator_cannot_own";

    // ---- storage / upload -----------------------------------------------
    public static final String STORAGE_R2_UNAVAILABLE = "storage.r2_unavailable";
    public static final String UPLOAD_TOO_LARGE = "upload.too_large";
    public static final String UPLOAD_INVALID_KIND = "upload.invalid_kind";
    public static final String UPLOAD_IMAGE_DIMENSIONS_TOO_LARGE = "upload.image_dimensions_too_large";
    public static final String UPLOAD_INVALID_DURATION = "upload.invalid_duration";

    // ---- semantic search (XEYE) -----------------------------------------
    public static final String SEARCH_NOT_CONFIGURED = "search.not_configured";
    public static final String SEARCH_UNAVAILABLE = "search.unavailable";

    // ---- OAuth authorization server / MCP --------------------------------
    public static final String OAUTH_APP_NOT_FOUND = "oauth.app_not_found";
    public static final String OAUTH_REGISTRATION_DISABLED = "oauth.registration_disabled";
    public static final String AUTH_INSUFFICIENT_SCOPE = "auth.insufficient_scope";
    public static final String MCP_TOOL_NOT_FOUND = "mcp.tool_not_found";
    public static final String MCP_INVALID_ARGUMENTS = "mcp.invalid_arguments";

    // ---- generic --------------------------------------------------------
    public static final String VALIDATION_REQUIRED = "validation.required";
    public static final String VALIDATION_FAILED = "validation.failed";
    public static final String SERVER_INTERNAL_ERROR = "server.internal_error";
    public static final String REQUEST_MALFORMED_BODY = "request.malformed_body";
    public static final String REQUEST_RATE_LIMITED = "request.rate_limited";
}
