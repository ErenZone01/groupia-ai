--liquibase formatted sql

--changeset groupia:043-direct-order-preparation-gate dbms:postgresql
-- PREPARING records internal packing work; it never authorizes pickup or delivery.
ALTER TABLE direct_orders ADD COLUMN preparing_at TIMESTAMPTZ;
ALTER TABLE direct_orders ADD COLUMN preparing_by UUID;

ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_status;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_status CHECK (
    status IN ('PENDING_PAYMENT_INTEGRATION','CANCELLED','PAYMENT_CONFIRMED','PREPARING')
);

ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_cancellation;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_cancellation CHECK (
    (status='PENDING_PAYMENT_INTEGRATION'
        AND cancelled_at IS NULL AND cancelled_by IS NULL
        AND payment_confirmed_at IS NULL)
    OR (status='CANCELLED'
        AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL
        AND payment_confirmed_at IS NULL)
    OR (status IN ('PAYMENT_CONFIRMED','PREPARING')
        AND cancelled_at IS NULL AND cancelled_by IS NULL
        AND payment_confirmed_at IS NOT NULL)
);

ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_preparation CHECK (
    (status='PREPARING'
        AND preparing_at IS NOT NULL AND preparing_by IS NOT NULL
        AND preparing_at>=payment_confirmed_at)
    OR (status<>'PREPARING'
        AND preparing_at IS NULL AND preparing_by IS NULL)
);

--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_preparation;
--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_cancellation;
--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_status;
--rollback ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_status CHECK (status IN ('PENDING_PAYMENT_INTEGRATION','CANCELLED','PAYMENT_CONFIRMED'));
--rollback ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_cancellation CHECK ((status='PENDING_PAYMENT_INTEGRATION' AND cancelled_at IS NULL AND cancelled_by IS NULL AND payment_confirmed_at IS NULL) OR (status='CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND payment_confirmed_at IS NULL) OR (status='PAYMENT_CONFIRMED' AND cancelled_at IS NULL AND cancelled_by IS NULL AND payment_confirmed_at IS NOT NULL));
--rollback ALTER TABLE direct_orders DROP COLUMN preparing_by;
--rollback ALTER TABLE direct_orders DROP COLUMN preparing_at;
