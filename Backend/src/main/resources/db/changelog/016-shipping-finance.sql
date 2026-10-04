--liquibase formatted sql
--changeset groupia:016-shipping-finance dbms:postgresql
ALTER TABLE payment_requests ALTER COLUMN expires_at DROP NOT NULL;
ALTER TABLE payment_requests ADD CONSTRAINT ck_payment_requests_initial_deadline
    CHECK (purpose <> 'INITIAL_ORDER' OR expires_at IS NOT NULL);
-- Existing date constraint still requires any non-null expiry to be after creation.
ALTER TABLE payment_requests ADD CONSTRAINT ck_payment_requests_shipping_breakdown
    CHECK (purpose <> 'SHIPPING_BALANCE' OR (expected_amount > 0 AND goods_amount=0 AND fee_amount=0 AND shipping_amount=expected_amount));
CREATE UNIQUE INDEX uk_payment_requests_shipping_open ON payment_requests(organization_id,order_id)
    WHERE purpose='SHIPPING_BALANCE' AND acceptance_status='OPEN';
