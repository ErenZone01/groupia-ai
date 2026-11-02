--liquibase formatted sql

--changeset groupia:036-direct-shipping-fees dbms:postgresql
-- Optional seller-defined fixed shipping fees per Senegalese ADM1 region.
-- No row or absent region means NOT QUOTED (never silently free shipping).
-- No order/payment table modified: the direct checkout remains disabled by default.
CREATE TABLE direct_shipping_fee_settings (
    organization_id UUID NOT NULL,
    storefront_id UUID NOT NULL,
    region_fees JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, storefront_id),
    CONSTRAINT fk_direct_shipping_fees_store FOREIGN KEY (organization_id, storefront_id)
        REFERENCES direct_storefronts (organization_id, id),
    CONSTRAINT ck_direct_shipping_fees_json CHECK (jsonb_typeof(region_fees) = 'object'),
    CONSTRAINT ck_direct_shipping_fees_version CHECK (version >= 0)
);

--rollback DROP TABLE direct_shipping_fee_settings;
