--liquibase formatted sql

--changeset groupia:032-direct-storefront dbms:postgresql
-- One tenant-scoped permanent public shop; independent of grouping campaigns.
CREATE TABLE direct_storefronts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    public_identifier UUID NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_direct_storefronts_organization UNIQUE (organization_id),
    CONSTRAINT uk_direct_storefronts_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_direct_storefronts_name CHECK (length(btrim(display_name)) > 0)
);

CREATE TABLE direct_offers (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    storefront_id UUID NOT NULL,
    product_variant_id UUID NOT NULL,
    price_xof BIGINT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_direct_offers_store FOREIGN KEY (organization_id, storefront_id)
        REFERENCES direct_storefronts(organization_id, id),
    CONSTRAINT fk_direct_offers_variant FOREIGN KEY (organization_id, product_variant_id)
        REFERENCES product_variants(organization_id, id),
    CONSTRAINT uk_direct_offers_variant UNIQUE (storefront_id, product_variant_id),
    CONSTRAINT ck_direct_offers_price CHECK (price_xof BETWEEN 1 AND 999999999999),
    CONSTRAINT ck_direct_offers_version CHECK (version >= 0)
);
CREATE INDEX ix_direct_offers_public ON direct_offers(storefront_id, published, created_at DESC, id);
--rollback DROP TABLE direct_offers;
--rollback DROP TABLE direct_storefronts;
