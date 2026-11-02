--liquibase formatted sql

--changeset groupia:035-direct-reverse-geocode-budget dbms:postgresql
-- Non-personal global counters for a cost-limited optional external geocoding API.
-- No GPS coordinates, client IP addresses, or provider responses are stored.
CREATE TABLE direct_geocode_quotas (
    bucket VARCHAR(48) PRIMARY KEY,
    used INTEGER NOT NULL DEFAULT 0 CHECK (used >= 0),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_direct_geocode_quotas_expiry ON direct_geocode_quotas (expires_at);

--rollback DROP TABLE direct_geocode_quotas;
