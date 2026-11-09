--liquibase formatted sql
--changeset groupia:050-commercial-cancellation-states dbms:postgresql
--validCheckSum: 9:cee7dcfde2808586052973a6183bfaa5
ALTER TABLE orders ADD COLUMN cancellation_case_id UUID;
ALTER TABLE orders ADD CONSTRAINT fk_order_cancellation_case FOREIGN KEY(organization_id,cancellation_case_id) REFERENCES commercial_refund_cases(organization_id,id);
ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK(status IN ('PENDING_PAYMENT','CONFIRMED','EXPIRED','CANCELLED','COMPLETED','REFUND_PENDING','REFUNDED'));
ALTER TABLE orders ADD CONSTRAINT ck_orders_refund_state CHECK(status NOT IN ('REFUND_PENDING','REFUNDED') OR
 (cancellation_case_id IS NOT NULL AND cancelled_at IS NOT NULL AND fulfillment_status='CANCELLED' AND picked_up_at IS NULL));
ALTER TABLE direct_orders ADD COLUMN cancellation_case_id UUID;
ALTER TABLE direct_orders ADD CONSTRAINT fk_direct_cancellation_case FOREIGN KEY(organization_id,cancellation_case_id) REFERENCES commercial_refund_cases(organization_id,id);
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_status;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_status CHECK(status IN ('PENDING_PAYMENT_INTEGRATION','CANCELLED','PAYMENT_CONFIRMED','PREPARING','READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','PICKED_UP','DELIVERED','COMPLETED','REFUND_PENDING','REFUNDED'));
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_cancellation;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_cancellation CHECK(
 (status='PENDING_PAYMENT_INTEGRATION' AND cancelled_at IS NULL AND cancelled_by IS NULL AND payment_confirmed_at IS NULL)
 OR(status='CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND payment_confirmed_at IS NULL)
 OR(status IN ('REFUND_PENDING','REFUNDED') AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND payment_confirmed_at IS NOT NULL AND cancellation_case_id IS NOT NULL AND cancelled_at>=payment_confirmed_at)
 OR(status NOT IN ('PENDING_PAYMENT_INTEGRATION','CANCELLED','REFUND_PENDING','REFUNDED') AND cancelled_at IS NULL AND cancelled_by IS NULL AND payment_confirmed_at IS NOT NULL));
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_preparation;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_preparation CHECK(
 (status IN ('PREPARING','READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','PICKED_UP','DELIVERED','COMPLETED') AND preparing_at IS NOT NULL AND preparing_by IS NOT NULL AND preparing_at>=payment_confirmed_at)
 OR(status IN ('REFUND_PENDING','REFUNDED') AND ((preparing_at IS NULL AND preparing_by IS NULL) OR (preparing_at IS NOT NULL AND preparing_by IS NOT NULL AND preparing_at>=payment_confirmed_at)))
 OR(status NOT IN ('PREPARING','READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','PICKED_UP','DELIVERED','COMPLETED','REFUND_PENDING','REFUNDED') AND preparing_at IS NULL AND preparing_by IS NULL));
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_ready;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_ready CHECK(
 (status IN ('READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','PICKED_UP','DELIVERED','COMPLETED') AND ready_at IS NOT NULL AND ready_by IS NOT NULL AND ready_at>=preparing_at
  AND (status NOT IN ('READY_FOR_PICKUP','PICKED_UP') OR fulfillment_method='PICKUP') AND (status NOT IN ('READY_FOR_DISPATCH','DISPATCHED','DELIVERED') OR fulfillment_method='DELIVERY'))
 OR(status IN ('REFUND_PENDING','REFUNDED') AND ((ready_at IS NULL AND ready_by IS NULL) OR (ready_at IS NOT NULL AND ready_by IS NOT NULL AND preparing_at IS NOT NULL AND ready_at>=preparing_at)))
 OR(status NOT IN ('READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','PICKED_UP','DELIVERED','COMPLETED','REFUND_PENDING','REFUNDED') AND ready_at IS NULL AND ready_by IS NULL));
