--liquibase formatted sql

--changeset groupia:042-direct-financial-confirmation dbms:postgresql
-- PAYMENT_CONFIRMED means canonical settlement was recorded, not shipping permission.
ALTER TABLE direct_orders ADD COLUMN payment_confirmed_at TIMESTAMPTZ;
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_status;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_status CHECK (
    status IN ('PENDING_PAYMENT_INTEGRATION','CANCELLED','PAYMENT_CONFIRMED')
);
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_cancellation;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_cancellation CHECK (
    (status='PENDING_PAYMENT_INTEGRATION'
        AND cancelled_at IS NULL AND cancelled_by IS NULL
        AND payment_confirmed_at IS NULL)
    OR (status='CANCELLED'
        AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL
        AND payment_confirmed_at IS NULL)
    OR (status='PAYMENT_CONFIRMED'
        AND cancelled_at IS NULL AND cancelled_by IS NULL
        AND payment_confirmed_at IS NOT NULL)
);

--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_cancellation;
--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_status;
--rollback ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_status CHECK (status IN ('PENDING_PAYMENT_INTEGRATION','CANCELLED'));
--rollback ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_cancellation CHECK ((status='PENDING_PAYMENT_INTEGRATION' AND cancelled_at IS NULL AND cancelled_by IS NULL) OR (status='CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL));
--rollback ALTER TABLE direct_orders DROP COLUMN payment_confirmed_at;
