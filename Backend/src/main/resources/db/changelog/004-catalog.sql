--liquibase formatted sql

--changeset groupia:004-catalog dbms:postgresql
CREATE TABLE products (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(5000),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_products_organization FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT uk_products_organization_id UNIQUE (organization_id, id),
    CONSTRAINT ck_products_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_products_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_products_dates CHECK (updated_at >= created_at),
    CONSTRAINT ck_products_version CHECK (version >= 0)
);
CREATE INDEX ix_products_organization_status_created
    ON products(organization_id, status, created_at DESC, id);
CREATE INDEX ix_products_organization_created
    ON products(organization_id, created_at DESC, id);

CREATE TABLE product_variants (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    product_id UUID NOT NULL,
    sku VARCHAR(64),
    attributes JSONB NOT NULL,
    canonical_variant_key VARCHAR(67) NOT NULL,
    sale_unit VARCHAR(16) NOT NULL,
    estimated_unit_weight_kg NUMERIC,
    estimated_unit_volume_m3 NUMERIC,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_variants_product_organization FOREIGN KEY (organization_id, product_id)
        REFERENCES products(organization_id, id),
    CONSTRAINT uk_variants_product_key UNIQUE (product_id, canonical_variant_key),
    CONSTRAINT uk_variants_organization_sku UNIQUE (organization_id, sku),
    CONSTRAINT ck_variants_sku CHECK (sku IS NULL OR sku ~ '^[A-Z0-9][A-Z0-9._/-]{0,63}$'),
    CONSTRAINT ck_variants_attributes CHECK (
        jsonb_typeof(attributes) = 'object'
        AND NOT jsonb_path_exists(attributes, '$.* ? (@.type() != "string")')),
    CONSTRAINT ck_variants_key CHECK (
        (attributes = '{}'::jsonb AND canonical_variant_key = 'DEFAULT')
        OR (attributes <> '{}'::jsonb AND canonical_variant_key ~ '^v1:[0-9a-f]{64}$')),
    CONSTRAINT ck_variants_sale_unit CHECK (sale_unit IN ('UNIT', 'PAIR', 'SET', 'PACK')),
    CONSTRAINT ck_variants_weight CHECK (estimated_unit_weight_kg IS NULL
        OR (estimated_unit_weight_kg > 0 AND estimated_unit_weight_kg < 'Infinity'::numeric)),
    CONSTRAINT ck_variants_volume CHECK (estimated_unit_volume_m3 IS NULL
        OR (estimated_unit_volume_m3 > 0 AND estimated_unit_volume_m3 < 'Infinity'::numeric)),
    CONSTRAINT ck_variants_dates CHECK (updated_at >= created_at)
);
CREATE INDEX ix_variants_product_active ON product_variants(product_id, active);
--rollback DROP TABLE product_variants;
--rollback DROP TABLE products;
