--liquibase formatted sql
--changeset groupia:046-direct-order-fulfillment dbms:postgresql
ALTER TABLE direct_orders ADD COLUMN dispatched_at TIMESTAMPTZ;
ALTER TABLE direct_orders ADD COLUMN handed_over_at TIMESTAMPTZ;
ALTER TABLE direct_orders ADD COLUMN completed_at TIMESTAMPTZ;
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_status;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_status CHECK (status IN
 ('PENDING_PAYMENT_INTEGRATION','CANCELLED','PAYMENT_CONFIRMED','PREPARING',
  'READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','PICKED_UP','DELIVERED','COMPLETED'));
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_cancellation;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_cancellation CHECK (
 (status='PENDING_PAYMENT_INTEGRATION' AND cancelled_at IS NULL AND cancelled_by IS NULL AND payment_confirmed_at IS NULL)
 OR (status='CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND payment_confirmed_at IS NULL)
 OR (status NOT IN ('PENDING_PAYMENT_INTEGRATION','CANCELLED') AND cancelled_at IS NULL AND cancelled_by IS NULL AND payment_confirmed_at IS NOT NULL));
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_preparation;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_preparation CHECK (
 (status IN ('PREPARING','READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','PICKED_UP','DELIVERED','COMPLETED')
  AND preparing_at IS NOT NULL AND preparing_by IS NOT NULL AND preparing_at>=payment_confirmed_at)
 OR (status NOT IN ('PREPARING','READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','PICKED_UP','DELIVERED','COMPLETED')
  AND preparing_at IS NULL AND preparing_by IS NULL));
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_ready;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_ready CHECK (
 (status IN ('READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','PICKED_UP','DELIVERED','COMPLETED')
  AND ready_at IS NOT NULL AND ready_by IS NOT NULL AND ready_at>=preparing_at
  AND (status NOT IN ('READY_FOR_PICKUP','PICKED_UP') OR fulfillment_method='PICKUP')
  AND (status NOT IN ('READY_FOR_DISPATCH','DISPATCHED','DELIVERED') OR fulfillment_method='DELIVERY'))
 OR (status NOT IN ('READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','PICKED_UP','DELIVERED','COMPLETED')
  AND ready_at IS NULL AND ready_by IS NULL));
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_handoff CHECK (
 (dispatched_at IS NULL OR (fulfillment_method='DELIVERY' AND dispatched_at>=ready_at))
 AND (handed_over_at IS NULL OR handed_over_at>=ready_at)
 AND (completed_at IS NULL OR completed_at>=handed_over_at)
 AND ((status IN ('DISPATCHED','DELIVERED') OR (status='COMPLETED' AND fulfillment_method='DELIVERY')) = (dispatched_at IS NOT NULL))
 AND ((status IN ('PICKED_UP','DELIVERED','COMPLETED')) = (handed_over_at IS NOT NULL))
 AND ((status='COMPLETED') = (completed_at IS NOT NULL))
 AND (fulfillment_method<>'DELIVERY' OR handed_over_at IS NULL OR handed_over_at>=dispatched_at));
CREATE TABLE direct_order_fulfillment_events (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id),
 direct_order_id UUID NOT NULL, actor_id UUID NOT NULL,
 FOREIGN KEY(organization_id,direct_order_id) REFERENCES direct_orders(organization_id,id),
 idempotency_key VARCHAR(128) NOT NULL, from_status VARCHAR(40) NOT NULL,
 to_status VARCHAR(40) NOT NULL CHECK(to_status IN ('DISPATCHED','PICKED_UP','DELIVERED','COMPLETED')),
 occurred_at TIMESTAMPTZ NOT NULL, expected_version BIGINT NOT NULL,
 UNIQUE(organization_id,direct_order_id,idempotency_key), UNIQUE(direct_order_id,to_status));
CREATE TRIGGER direct_fulfillment_history BEFORE UPDATE OR DELETE ON direct_order_fulfillment_events
 FOR EACH ROW EXECUTE FUNCTION guard_direct_verified_settlement_history();
-- Forward-only operational history: rollback would discard recorded physical handovers.
