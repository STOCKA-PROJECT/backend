-- MariaDB Connector/J reports BLOB columns as LONGVARBINARY and refuses the String values that
-- Spring Authorization Server's JdbcOAuth2AuthorizationService binds to them
-- ("Could not convert [...] to -4"), so no authorization could be saved on MariaDB (V22 used BLOB).
-- The stored values are text (JSON maps, JWTs, opaque tokens): LONGTEXT is reported as LONGVARCHAR
-- and the service reads/writes it with getString/setString. H2 keeps BLOB in tests
-- (src/test/resources/oauth2-schema-h2.sql) because H2 reports Types.BLOB, which the service handles.
-- Re-runnable: MODIFY COLUMN to the same type is a no-op.
ALTER TABLE oauth2_authorization
    MODIFY COLUMN attributes LONGTEXT NULL DEFAULT NULL,
    MODIFY COLUMN authorization_code_value LONGTEXT NULL DEFAULT NULL,
    MODIFY COLUMN authorization_code_metadata LONGTEXT NULL DEFAULT NULL,
    MODIFY COLUMN access_token_value LONGTEXT NULL DEFAULT NULL,
    MODIFY COLUMN access_token_metadata LONGTEXT NULL DEFAULT NULL,
    MODIFY COLUMN oidc_id_token_value LONGTEXT NULL DEFAULT NULL,
    MODIFY COLUMN oidc_id_token_metadata LONGTEXT NULL DEFAULT NULL,
    MODIFY COLUMN refresh_token_value LONGTEXT NULL DEFAULT NULL,
    MODIFY COLUMN refresh_token_metadata LONGTEXT NULL DEFAULT NULL,
    MODIFY COLUMN user_code_value LONGTEXT NULL DEFAULT NULL,
    MODIFY COLUMN user_code_metadata LONGTEXT NULL DEFAULT NULL,
    MODIFY COLUMN device_code_value LONGTEXT NULL DEFAULT NULL,
    MODIFY COLUMN device_code_metadata LONGTEXT NULL DEFAULT NULL;
