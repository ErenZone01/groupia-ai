--liquibase formatted sql

--changeset groupia:038-direct-order-access-capabilities dbms:postgresql
-- Bearer-capability hash only. Never persist a plaintext customer access token.
-- Existing direct orders remain intact and inaccessible without a valid token.
ALTER TABLE direct_orders ADD COLUMN recovery_token_hash CHAR(64);
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_recovery_hash CHECK (
    recovery_token_hash IS NULL OR recovery_token_hash ~ '^[0-9a-f]{64}$'
);
CREATE UNIQUE INDEX ux_direct_orders_recovery_token_hash
    ON direct_orders(recovery_token_hash) WHERE recovery_token_hash IS NOT NULL;

--rollback DROP INDEX ux_direct_orders_recovery_token_hash;
--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_recovery_hash;
--rollback ALTER TABLE direct_orders DROP COLUMN recovery_token_hash;
