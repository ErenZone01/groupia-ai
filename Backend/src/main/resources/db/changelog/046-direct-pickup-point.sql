--liquibase formatted sql

--changeset groupia:046-direct-pickup-point dbms:postgresql
-- A public pickup destination is a merchant-managed storefront fact.
-- Old orders retain NULL snapshots: their historical pickup location is unknown.
ALTER TABLE direct_storefronts
    ADD COLUMN pickup_address VARCHAR(400),
    ADD COLUMN pickup_instructions VARCHAR(500),
    ADD COLUMN pickup_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_direct_storefront_pickup CHECK (
        pickup_version >= 0
        AND (pickup_address IS NULL OR length(btrim(pickup_address)) BETWEEN 5 AND 400)
        AND (pickup_instructions IS NULL OR
             (pickup_address IS NOT NULL AND length(btrim(pickup_instructions)) BETWEEN 1 AND 500))
    );

ALTER TABLE direct_orders
    ADD COLUMN pickup_address_snapshot VARCHAR(400),
    ADD COLUMN pickup_instructions_snapshot VARCHAR(500),
    ADD CONSTRAINT ck_direct_order_pickup_snapshot CHECK (
        (fulfillment_method = 'PICKUP'
            AND (pickup_address_snapshot IS NULL
                 OR length(btrim(pickup_address_snapshot)) BETWEEN 5 AND 400)
            AND (pickup_instructions_snapshot IS NULL
                 OR (pickup_address_snapshot IS NOT NULL
                     AND length(btrim(pickup_instructions_snapshot)) BETWEEN 1 AND 500)))
        OR (fulfillment_method = 'DELIVERY'
            AND pickup_address_snapshot IS NULL AND pickup_instructions_snapshot IS NULL)
    );

-- Migration is additive; never backfill a fictitious pickup location into an old order.
