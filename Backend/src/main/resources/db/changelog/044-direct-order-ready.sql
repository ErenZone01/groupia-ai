--liquibase formatted sql

--changeset groupia:044-direct-order-ready dbms:postgresql
-- READY means packing is complete. Dispatch, pickup, delivery and payout stay forbidden.
ALTER TABLE direct_orders ADD COLUMN ready_at TIMESTAMPTZ;
ALTER TABLE direct_orders ADD COLUMN ready_by UUID;

ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_status;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_status CHECK (
    status IN ('PENDING_PAYMENT_INTEGRATION','CANCELLED','PAYMENT_CONFIRMED',
               'PREPARING','READY_FOR_PICKUP','READY_FOR_DISPATCH')
);

ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_cancellation;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_cancellation CHECK (
    (status='PENDING_PAYMENT_INTEGRATION'
        AND cancelled_at IS NULL AND cancelled_by IS NULL
        AND payment_confirmed_at IS NULL)
    OR (status='CANCELLED'
        AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL
        AND payment_confirmed_at IS NULL)
    OR (status IN ('PAYMENT_CONFIRMED','PREPARING','READY_FOR_PICKUP','READY_FOR_DISPATCH')
        AND cancelled_at IS NULL AND cancelled_by IS NULL
        AND payment_confirmed_at IS NOT NULL)
);

ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_preparation;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_preparation CHECK (
    (status IN ('PREPARING','READY_FOR_PICKUP','READY_FOR_DISPATCH')
        AND preparing_at IS NOT NULL AND preparing_by IS NOT NULL
        AND preparing_at>=payment_confirmed_at)
    OR (status NOT IN ('PREPARING','READY_FOR_PICKUP','READY_FOR_DISPATCH')
        AND preparing_at IS NULL AND preparing_by IS NULL)
);

ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_ready CHECK (
    (status='READY_FOR_PICKUP' AND fulfillment_method='PICKUP'
        AND ready_at IS NOT NULL AND ready_by IS NOT NULL
        AND ready_at>=preparing_at)
    OR (status='READY_FOR_DISPATCH' AND fulfillment_method='DELIVERY'
        AND ready_at IS NOT NULL AND ready_by IS NOT NULL
        AND ready_at>=preparing_at)
    OR (status NOT IN ('READY_FOR_PICKUP','READY_FOR_DISPATCH')
        AND ready_at IS NULL AND ready_by IS NULL)
);

--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_ready;
--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_preparation;
--rollback ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_preparation CHECK ((status='PREPARING' AND preparing_at IS NOT NULL AND preparing_by IS NOT NULL AND preparing_at>=payment_confirmed_at) OR (status<>'PREPARING' AND preparing_at IS NULL AND preparing_by IS NULL));
--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_cancellation;
--rollback ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_cancellation CHECK ((status='PENDING_PAYMENT_INTEGRATION' AND cancelled_at IS NULL AND cancelled_by IS NULL AND payment_confirmed_at IS NULL) OR (status='CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND payment_confirmed_at IS NULL) OR (status IN ('PAYMENT_CONFIRMED','PREPARING') AND cancelled_at IS NULL AND cancelled_by IS NULL AND payment_confirmed_at IS NOT NULL));
--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_status;
--rollback ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_status CHECK (status IN ('PENDING_PAYMENT_INTEGRATION','CANCELLED','PAYMENT_CONFIRMED','PREPARING'));
--rollback ALTER TABLE direct_orders DROP COLUMN ready_by;
--rollback ALTER TABLE direct_orders DROP COLUMN ready_at;
