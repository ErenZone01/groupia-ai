--liquibase formatted sql

--changeset groupia:037-direct-checkout-accepted-totals dbms:postgresql
-- Additive, nullable snapshots: old unpaid drafts remain readable unchanged.
-- Never authorize payment based on these fields alone.
ALTER TABLE direct_orders
    ADD COLUMN shipping_fee_xof BIGINT,
    ADD COLUMN accepted_total_xof BIGINT,
    ADD COLUMN shipping_rate_version BIGINT;

ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_shipping;
ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_shipping CHECK (
    (fulfillment_method = 'PICKUP' AND shipping_quoted = true
        AND delivery_address_snapshot IS NULL)
    OR (fulfillment_method = 'DELIVERY'
        AND jsonb_typeof(delivery_address_snapshot) = 'object')
);

ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_accepted_totals CHECK (
    (shipping_fee_xof IS NULL AND accepted_total_xof IS NULL
        AND shipping_rate_version IS NULL)
    OR (shipping_fee_xof BETWEEN 0 AND 100000
        AND accepted_total_xof BETWEEN 1 AND 99999999999999
        AND accepted_total_xof = amount_before_shipping_xof + shipping_fee_xof
        AND shipping_quoted = true
        AND (fulfillment_method = 'PICKUP' OR shipping_rate_version >= 0))
);

--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_accepted_totals;
--rollback ALTER TABLE direct_orders DROP CONSTRAINT ck_direct_orders_shipping;
--rollback ALTER TABLE direct_orders ADD CONSTRAINT ck_direct_orders_shipping CHECK ((fulfillment_method = 'PICKUP' AND shipping_quoted = true AND delivery_address_snapshot IS NULL) OR (fulfillment_method = 'DELIVERY' AND shipping_quoted = false AND jsonb_typeof(delivery_address_snapshot) = 'object'));
--rollback ALTER TABLE direct_orders DROP COLUMN shipping_rate_version;
--rollback ALTER TABLE direct_orders DROP COLUMN accepted_total_xof;
--rollback ALTER TABLE direct_orders DROP COLUMN shipping_fee_xof;
