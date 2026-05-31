-- Feature tables created by V8–V12 were written with `id BIGINT AUTO_INCREMENT`,
-- but every entity uses @GeneratedValue(strategy = AUTO). On MariaDB (which
-- supports sequences) Hibernate 6/7 maps AUTO to a per-table sequence named
-- `<table>_seq`, so `ddl-auto=validate` in prod fails with
-- "missing sequence [<table>_seq]" for tables that were never created by
-- ddl-auto (which would have created the sequence alongside the table).
--
-- These six tables are the ones Flyway created on the first real migration run;
-- the 22 older tables already carry their sequence (created by ddl-auto). The
-- tables are empty, so START WITH 1 is safe. INCREMENT BY 50 matches Hibernate's
-- default allocationSize for AUTO/sequence generators.
-- Definition mirrors the sequences Hibernate/ddl-auto already created for the
-- 22 older tables (verified against users_seq): increment 50, nocache, nocycle.
CREATE SEQUENCE IF NOT EXISTS oauth_identities_seq          START WITH 1 INCREMENT BY 50 NOCACHE NOCYCLE;
CREATE SEQUENCE IF NOT EXISTS user_devices_seq              START WITH 1 INCREMENT BY 50 NOCACHE NOCYCLE;
CREATE SEQUENCE IF NOT EXISTS two_factor_recovery_codes_seq START WITH 1 INCREMENT BY 50 NOCACHE NOCYCLE;
CREATE SEQUENCE IF NOT EXISTS two_factor_setup_tokens_seq   START WITH 1 INCREMENT BY 50 NOCACHE NOCYCLE;
CREATE SEQUENCE IF NOT EXISTS refresh_tokens_seq            START WITH 1 INCREMENT BY 50 NOCACHE NOCYCLE;
CREATE SEQUENCE IF NOT EXISTS security_audit_entries_seq    START WITH 1 INCREMENT BY 50 NOCACHE NOCYCLE;
