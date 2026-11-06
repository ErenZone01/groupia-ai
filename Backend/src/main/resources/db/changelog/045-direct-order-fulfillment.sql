--liquibase formatted sql

--changeset groupia:045-direct-order-fulfillment dbms:postgresql
ALTER TABLE direct_orders
    ADD COLUMN dispatched_at TIMESTAMPTZ,
    ADD COLUMN dispatched_by UUID,
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN completed_by UUID;

ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_status;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_status CHECK (
    status IN ('PENDING_PAYMENT_INTEGRATION','CANCELLED','PAYMENT_CONFIRMED',
               'PREPARING','READY_FOR_PICKUP','READY_FOR_DISPATCH',
               'DISPATCHED','DELIVERED','PICKED_UP')
);
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_cancellation;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_cancellation CHECK (
    (status='PENDING_PAYMENT_INTEGRATION' AND cancelled_at IS NULL
        AND cancelled_by IS NULL AND payment_confirmed_at IS NULL)
    OR (status='CANCELLED' AND cancelled_at IS NOT NULL
        AND cancelled_by IS NOT NULL AND payment_confirmed_at IS NULL)
    OR (status NOT IN ('PENDING_PAYMENT_INTEGRATION','CANCELLED')
        AND cancelled_at IS NULL AND cancelled_by IS NULL AND payment_confirmed_at IS NOT NULL)
);
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_preparation;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_preparation CHECK (
    (status IN ('PREPARING','READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','DELIVERED','PICKED_UP')
        AND preparing_at IS NOT NULL AND preparing_by IS NOT NULL AND preparing_at>=payment_confirmed_at)
    OR (status NOT IN ('PREPARING','READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','DELIVERED','PICKED_UP')
        AND preparing_at IS NULL AND preparing_by IS NULL)
);
ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_ready;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_ready CHECK (
    (status IN ('READY_FOR_PICKUP','PICKED_UP') AND fulfillment_method='PICKUP'
        AND ready_at IS NOT NULL AND ready_by IS NOT NULL AND ready_at>=preparing_at)
    OR (status IN ('READY_FOR_DISPATCH','DISPATCHED','DELIVERED') AND fulfillment_method='DELIVERY'
        AND ready_at IS NOT NULL AND ready_by IS NOT NULL AND ready_at>=preparing_at)
    OR (status NOT IN ('READY_FOR_PICKUP','READY_FOR_DISPATCH','DISPATCHED','DELIVERED','PICKED_UP')
        AND ready_at IS NULL AND ready_by IS NULL)
);
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_dispatch CHECK (
    (status IN ('DISPATCHED','DELIVERED') AND fulfillment_method='DELIVERY'
        AND dispatched_at IS NOT NULL AND dispatched_by IS NOT NULL AND dispatched_at>=ready_at)
    OR (status NOT IN ('DISPATCHED','DELIVERED') AND dispatched_at IS NULL AND dispatched_by IS NULL)
);
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_completion CHECK (
    (status='DELIVERED' AND completed_at IS NOT NULL AND completed_by IS NOT NULL
        AND completed_at>=dispatched_at)
    OR (status='PICKED_UP' AND completed_at IS NOT NULL AND completed_by IS NOT NULL
        AND completed_at>=ready_at)
    OR (status NOT IN ('DELIVERED','PICKED_UP') AND completed_at IS NULL AND completed_by IS NULL)
);

CREATE TABLE direct_order_fulfillment_events (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    direct_order_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    actor_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payment_request_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    CONSTRAINT fk_direct_fulfillment_order FOREIGN KEY (organization_id,direct_order_id)
        REFERENCES direct_orders(organization_id,id),
    CONSTRAINT uk_direct_fulfillment_action UNIQUE (organization_id,direct_order_id,action),
    CONSTRAINT ck_direct_fulfillment_action CHECK (
        (action='DISPATCH' AND status='DISPATCHED')
        OR (action='DELIVER' AND status='DELIVERED')
        OR (action='PICKUP' AND status='PICKED_UP'))
);

--changeset groupia:045-direct-fulfillment-immutable dbms:postgresql splitStatements:false
CREATE FUNCTION reject_direct_fulfillment_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Direct fulfillment history is immutable';
END;
$$;

--changeset groupia:045-direct-fulfillment-trigger dbms:postgresql
CREATE TRIGGER trg_direct_fulfillment_immutable BEFORE UPDATE OR DELETE
    ON direct_order_fulfillment_events FOR EACH ROW EXECUTE FUNCTION reject_direct_fulfillment_mutation();
