--liquibase formatted sql
--changeset groupia:023-auth-rate-limits dbms:postgresql
CREATE TABLE auth_rate_limits (
    scope varchar(24) NOT NULL CHECK (scope IN ('login-network','login-subject','register-network','register-subject')),
    key_hash char(64) NOT NULL CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    attempts integer NOT NULL CHECK (attempts > 0),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (scope, key_hash)
);
CREATE INDEX ix_auth_rate_limits_expiry ON auth_rate_limits(expires_at);
