-- Sessions panel removed (2026-10-29): "Connected devices" in the account screen listed, renamed and
-- revoked the refresh-token families through user_devices. The logins and their IP stay in
-- security_audit_entries (LOGIN_SUCCESS); refresh tokens keep working on their own. The two event
-- types only the panel emitted leave the enum, so their rows go too (NEW_DEVICE_LOGIN repeated every
-- LOGIN_SUCCESS).

DROP TABLE IF EXISTS user_devices;
DROP SEQUENCE IF EXISTS user_devices_seq;

DELETE FROM security_audit_entries WHERE event_type IN ('NEW_DEVICE_LOGIN', 'SESSION_REVOKED');
