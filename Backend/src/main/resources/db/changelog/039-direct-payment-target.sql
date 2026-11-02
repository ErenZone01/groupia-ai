--liquibase formatted sql
--changeset groupia:039-direct-payment-target dbms:postgresql
-- A direct sale is NOT an ordering/groupage order; keep tenant-bound foreign keys separate.
-- This only permits durable requests. The payment initiation gate remains closed in Java.
ALTER TABLE payment_requests ADD COLUMN direct_order_id UUID;
ALTER TABLE payment_requests ADD CONSTRAINT fk_payment_requests_direct_order
    FOREIGN KEY (organization_id, direct_order_id)
    REFERENCES direct_orders(organization_id, id);

ALTER TABLE payment_requests DROP CONSTRAINT ck_payment_requests_target;
ALTER TABLE payment_requests ADD CONSTRAINT ck_payment_requests_target CHECK (
    (purpose IN ('INITIAL_ORDER','SHIPPING_BALANCE')
        AND target_type='ORDER' AND order_id IS NOT NULL
        AND target_id=order_id AND direct_order_id IS NULL)
    OR (purpose='SUBSCRIPTION_MONTHLY'
        AND target_type='SUBSCRIPTION_INVOICE'
        AND order_id IS NULL AND direct_order_id IS NULL)
    OR (purpose='DIRECT_ORDER_INITIAL'
        AND target_type='DIRECT_ORDER'
        AND order_id IS NULL AND direct_order_id IS NOT NULL
        AND target_id=direct_order_id)
);

ALTER TABLE payment_requests DROP CONSTRAINT ck_payment_requests_amounts;
ALTER TABLE payment_requests ADD CONSTRAINT ck_payment_requests_amounts CHECK (
    expected_amount>=0 AND expected_amount<1000000000000000000 AND expected_amount=trunc(expected_amount)
    AND goods_amount>=0 AND goods_amount<1000000000000000000 AND goods_amount=trunc(goods_amount)
    AND fee_amount>=0 AND fee_amount<1000000000000000000 AND fee_amount=trunc(fee_amount)
    AND shipping_amount>=0 AND shipping_amount<1000000000000000000 AND shipping_amount=trunc(shipping_amount)
    AND ((target_type IN ('ORDER','DIRECT_ORDER')
        AND goods_amount+fee_amount+shipping_amount=expected_amount)
      OR (target_type='SUBSCRIPTION_INVOICE'
        AND expected_amount>0 AND goods_amount=0 AND fee_amount=0 AND shipping_amount=0))
);
ALTER TABLE payment_requests ADD CONSTRAINT ck_payment_requests_direct_positive CHECK (
    purpose<>'DIRECT_ORDER_INITIAL'
    OR (expected_amount>0 AND expected_amount<=99999999999999 AND expires_at IS NOT NULL)
);
CREATE UNIQUE INDEX uk_payment_direct_order_initial
    ON payment_requests(organization_id, direct_order_id)
    WHERE purpose='DIRECT_ORDER_INITIAL';

--rollback DROP INDEX uk_payment_direct_order_initial;
--rollback ALTER TABLE payment_requests DROP CONSTRAINT ck_payment_requests_direct_positive;
--rollback ALTER TABLE payment_requests DROP CONSTRAINT ck_payment_requests_amounts;
--rollback ALTER TABLE payment_requests ADD CONSTRAINT ck_payment_requests_amounts CHECK (expected_amount>=0 AND expected_amount<1000000000000000000 AND expected_amount=trunc(expected_amount) AND goods_amount>=0 AND goods_amount<1000000000000000000 AND goods_amount=trunc(goods_amount) AND fee_amount>=0 AND fee_amount<1000000000000000000 AND fee_amount=trunc(fee_amount) AND shipping_amount>=0 AND shipping_amount<1000000000000000000 AND shipping_amount=trunc(shipping_amount) AND ((target_type='ORDER' AND goods_amount+fee_amount+shipping_amount=expected_amount) OR (target_type='SUBSCRIPTION_INVOICE' AND expected_amount>0 AND goods_amount=0 AND fee_amount=0 AND shipping_amount=0)));
--rollback ALTER TABLE payment_requests DROP CONSTRAINT ck_payment_requests_target;
--rollback ALTER TABLE payment_requests ADD CONSTRAINT ck_payment_requests_target CHECK ((purpose IN ('INITIAL_ORDER','SHIPPING_BALANCE') AND target_type='ORDER' AND order_id IS NOT NULL AND target_id=order_id) OR (purpose='SUBSCRIPTION_MONTHLY' AND target_type='SUBSCRIPTION_INVOICE' AND order_id IS NULL));
--rollback ALTER TABLE payment_requests DROP CONSTRAINT fk_payment_requests_direct_order;
--rollback ALTER TABLE payment_requests DROP COLUMN direct_order_id;
