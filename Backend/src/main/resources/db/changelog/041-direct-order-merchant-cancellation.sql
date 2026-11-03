--liquibase formatted sql

--changeset groupia:041-direct-order-merchant-cancellation dbms:postgresql
-- Only the server may cancel an unpaid direct order. Retain immutable checkout amounts.
ALTER TABLE direct_orders ADD COLUMN cancelled_at TIMESTAMPTZ;
ALTER TABLE direct_orders ADD COLUMN cancelled_by UUID;
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_status;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_status CHECK (
    status IN ('PENDING_PAYMENT_INTEGRATION','CANCELLED')
);
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_cancellation CHECK (
    (status='PENDING_PAYMENT_INTEGRATION' AND cancelled_at IS NULL AND cancelled_by IS NULL)
    OR (status='CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL)
);

--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_cancellation;
--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_status;
--rollback ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_status CHECK (status='PENDING_PAYMENT_INTEGRATION');
--rollback ALTER TABLE direct_orders DROP COLUMN cancelled_by;
--rollback ALTER TABLE direct_orders DROP COLUMN cancelled_at;
