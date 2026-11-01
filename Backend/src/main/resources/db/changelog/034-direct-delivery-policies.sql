--liquibase formatted sql

--changeset groupia:034-direct-delivery-policies dbms:postgresql
-- Optional merchant-level rules for delivery by region. Absence of a row means DISABLED (pickup-only) until the merchant opts in.
-- This avoids implying that merchants deliver nationwide by default.
-- These settings do NOT establish the buyer's true location: GPS + server-side
-- geographic containment remains a prerequisite for verified regional coverage.
CREATE TABLE direct_delivery_policies (
    organization_id UUID NOT NULL,
    storefront_id UUID NOT NULL,
    mode VARCHAR(16) NOT NULL,
    allowed_regions JSONB NOT NULL DEFAULT '[]'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, storefront_id),
    CONSTRAINT fk_direct_delivery_policy_store FOREIGN KEY (organization_id, storefront_id)
        REFERENCES direct_storefronts (organization_id, id),
    CONSTRAINT ck_direct_delivery_policy_mode CHECK (mode IN ('ALL','SELECTED','DISABLED')),
    CONSTRAINT ck_direct_delivery_policy_version CHECK (version >= 0),
    CONSTRAINT ck_direct_delivery_policy_regions CHECK (
        jsonb_typeof(allowed_regions) = 'array'
        AND ((mode = 'SELECTED' AND jsonb_array_length(allowed_regions) BETWEEN 1 AND 14)
          OR (mode <> 'SELECTED' AND jsonb_array_length(allowed_regions) = 0)))
);

--rollback DROP TABLE direct_delivery_policies;
