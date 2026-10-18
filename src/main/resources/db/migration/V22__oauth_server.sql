-- OAuth 2.1 authorization server for MCP connectors.
--
-- The first three tables are the schema of Spring Authorization Server (JdbcRegisteredClientRepository,
-- JdbcOAuth2AuthorizationService, JdbcOAuth2AuthorizationConsentService) with MariaDB-friendly
-- timestamps plus three Stocka columns on the client table (registered_via, client_uri, logo_uri).
-- oauth2_cimd_clients maps the synthetic id of a Client ID Metadata Document client to its URL.
-- Re-runnable: every statement is guarded with IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS oauth2_registered_client (
    id VARCHAR(100) NOT NULL,
    client_id VARCHAR(100) NOT NULL,
    client_id_issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_secret VARCHAR(200) NULL DEFAULT NULL,
    client_secret_expires_at TIMESTAMP NULL DEFAULT NULL,
    client_name VARCHAR(200) NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types VARCHAR(1000) NOT NULL,
    redirect_uris VARCHAR(2000) NULL DEFAULT NULL,
    post_logout_redirect_uris VARCHAR(1000) NULL DEFAULT NULL,
    scopes VARCHAR(1000) NOT NULL,
    client_settings VARCHAR(2000) NOT NULL,
    token_settings VARCHAR(2000) NOT NULL,
    registered_via VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    client_uri VARCHAR(512) NULL DEFAULT NULL,
    logo_uri VARCHAR(512) NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_oauth2_registered_client_client_id (client_id)
);

CREATE TABLE IF NOT EXISTS oauth2_authorization (
    id VARCHAR(100) NOT NULL,
    registered_client_id VARCHAR(100) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    authorization_grant_type VARCHAR(100) NOT NULL,
    authorized_scopes VARCHAR(1000) NULL DEFAULT NULL,
    attributes BLOB NULL DEFAULT NULL,
    state VARCHAR(500) NULL DEFAULT NULL,
    authorization_code_value BLOB NULL DEFAULT NULL,
    authorization_code_issued_at TIMESTAMP NULL DEFAULT NULL,
    authorization_code_expires_at TIMESTAMP NULL DEFAULT NULL,
    authorization_code_metadata BLOB NULL DEFAULT NULL,
    access_token_value BLOB NULL DEFAULT NULL,
    access_token_issued_at TIMESTAMP NULL DEFAULT NULL,
    access_token_expires_at TIMESTAMP NULL DEFAULT NULL,
    access_token_metadata BLOB NULL DEFAULT NULL,
    access_token_type VARCHAR(100) NULL DEFAULT NULL,
    access_token_scopes VARCHAR(1000) NULL DEFAULT NULL,
    oidc_id_token_value BLOB NULL DEFAULT NULL,
    oidc_id_token_issued_at TIMESTAMP NULL DEFAULT NULL,
    oidc_id_token_expires_at TIMESTAMP NULL DEFAULT NULL,
    oidc_id_token_metadata BLOB NULL DEFAULT NULL,
    refresh_token_value BLOB NULL DEFAULT NULL,
    refresh_token_issued_at TIMESTAMP NULL DEFAULT NULL,
    refresh_token_expires_at TIMESTAMP NULL DEFAULT NULL,
    refresh_token_metadata BLOB NULL DEFAULT NULL,
    user_code_value BLOB NULL DEFAULT NULL,
    user_code_issued_at TIMESTAMP NULL DEFAULT NULL,
    user_code_expires_at TIMESTAMP NULL DEFAULT NULL,
    user_code_metadata BLOB NULL DEFAULT NULL,
    device_code_value BLOB NULL DEFAULT NULL,
    device_code_issued_at TIMESTAMP NULL DEFAULT NULL,
    device_code_expires_at TIMESTAMP NULL DEFAULT NULL,
    device_code_metadata BLOB NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_oauth2_authorization_principal (principal_name),
    KEY idx_oauth2_authorization_client (registered_client_id),
    KEY idx_oauth2_authorization_state (state)
);

CREATE TABLE IF NOT EXISTS oauth2_authorization_consent (
    registered_client_id VARCHAR(100) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    authorities VARCHAR(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

CREATE TABLE IF NOT EXISTS oauth2_cimd_clients (
    id VARCHAR(100) NOT NULL,
    client_id VARCHAR(2048) NOT NULL,
    first_seen_at TIMESTAMP NULL DEFAULT NULL,
    last_seen_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id)
);
