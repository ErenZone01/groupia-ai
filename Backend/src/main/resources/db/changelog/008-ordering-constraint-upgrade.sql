--liquibase formatted sql

--changeset groupia:008-ordering-constraint-upgrade dbms:postgresql
-- Converge the two known versions of 006 without replaying CREATE TABLE or changing historical data.
ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status
    CHECK (status IN ('PENDING_PAYMENT','CONFIRMED','EXPIRED','CANCELLED','COMPLETED'));

ALTER TABLE orders DROP CONSTRAINT ck_orders_fulfillment;
ALTER TABLE orders ADD CONSTRAINT ck_orders_fulfillment
    CHECK (fulfillment_status IN ('NOT_READY','READY_FOR_PICKUP','PICKED_UP','CANCELLED'));

ALTER TABLE orders DROP CONSTRAINT ck_orders_snapshots;
ALTER TABLE orders ADD CONSTRAINT ck_orders_snapshots CHECK (
    jsonb_typeof(customer_contact_snapshot) = 'object'
    AND jsonb_typeof(platform_fee_rule_snapshot) = 'object'
    AND jsonb_typeof(shipping_terms_snapshot) = 'object'
    AND jsonb_exists_all(customer_contact_snapshot, array['firstName','lastName','phoneE164'])
    AND jsonb_exists_all(platform_fee_rule_snapshot, array['type','currency','amount','version'])
    AND jsonb_exists_all(shipping_terms_snapshot, array['shippingCollectionMode','shippingPricingMethod','shippingRate','shippingCurrency','roundingRule']));

ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_snapshot_currency;
ALTER TABLE orders ADD CONSTRAINT ck_orders_snapshot_currency CHECK (
    coalesce(platform_fee_rule_snapshot->>'currency' = currency, false)
    AND coalesce(shipping_terms_snapshot->>'shippingCurrency' = currency, false));

-- No automatic rollback: restoring the old checks could reject valid EXPIRED/CANCELLED orders.
