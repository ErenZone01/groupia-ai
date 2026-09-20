--liquibase formatted sql

--changeset groupia:005-grouping dbms:postgresql
-- Composite target required by the new scalar cross-module FK; no data ownership change.
ALTER TABLE product_variants ADD CONSTRAINT uk_variants_organization_id UNIQUE (organization_id, id);

CREATE TABLE grouping_campaigns (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    public_identifier UUID NOT NULL,
    slug VARCHAR(160) NOT NULL,
    title VARCHAR(160) NOT NULL,
    description VARCHAR(5000),
    status VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    closes_at TIMESTAMP WITH TIME ZONE,
    origin_country VARCHAR(2),
    transport_mode VARCHAR(16),
    shipping_pricing_method VARCHAR(16),
    shipping_collection_mode VARCHAR(32),
    shipping_rate NUMERIC,
    shipping_currency VARCHAR(3),
    rounding_rule VARCHAR(16),
    opened_at TIMESTAMP WITH TIME ZONE,
    closed_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    cancellation_reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_grouping_campaigns_organization FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT uk_grouping_campaigns_organization_id UNIQUE (organization_id, id),
    CONSTRAINT uk_grouping_campaigns_public_identifier UNIQUE (public_identifier),
    CONSTRAINT ck_grouping_campaigns_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_grouping_campaigns_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_grouping_campaigns_currency CHECK (currency = 'XOF'),
    CONSTRAINT ck_grouping_campaigns_status CHECK (status IN
        ('DRAFT', 'OPEN', 'CLOSED', 'SUPPLIER_ORDERED', 'IN_TRANSIT', 'ARRIVED', 'DISTRIBUTING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_grouping_campaigns_terms_shape CHECK (
        (origin_country IS NULL AND transport_mode IS NULL AND shipping_pricing_method IS NULL
            AND shipping_collection_mode IS NULL AND shipping_rate IS NULL AND shipping_currency IS NULL AND rounding_rule IS NULL)
        OR (transport_mode IS NOT NULL AND shipping_pricing_method IS NOT NULL
            AND shipping_collection_mode IS NOT NULL AND shipping_rate IS NOT NULL AND shipping_currency IS NOT NULL AND rounding_rule IS NOT NULL)),
    CONSTRAINT ck_grouping_campaigns_origin CHECK (origin_country IS NULL OR origin_country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_grouping_campaigns_transport CHECK (transport_mode IN ('AIR', 'SEA', 'ROAD', 'OTHER')),
    CONSTRAINT ck_grouping_campaigns_method CHECK (shipping_pricing_method IN ('PER_KG', 'PER_CBM', 'PER_ITEM', 'FIXED')),
    CONSTRAINT ck_grouping_campaigns_collection CHECK (shipping_collection_mode IN ('ESTIMATED_AT_CHECKOUT', 'AFTER_FINAL_ASSESSMENT')),
    CONSTRAINT ck_grouping_campaigns_rate CHECK (shipping_rate IS NULL OR (
        shipping_rate >= 0 AND shipping_rate < 1000000000000000000
        AND (shipping_pricing_method = 'FIXED' OR shipping_rate > 0)
        AND (shipping_pricing_method <> 'FIXED' OR shipping_rate = trunc(shipping_rate)))),
    CONSTRAINT ck_grouping_campaigns_shipping_currency CHECK (shipping_currency = 'XOF'),
    CONSTRAINT ck_grouping_campaigns_rounding CHECK (rounding_rule = 'HALF_UP'),
    CONSTRAINT ck_grouping_campaigns_publication CHECK (
        status IN ('DRAFT', 'CANCELLED') OR (opened_at IS NOT NULL AND transport_mode IS NOT NULL)),
    CONSTRAINT ck_grouping_campaigns_draft CHECK (status <> 'DRAFT' OR opened_at IS NULL),
    CONSTRAINT ck_grouping_campaigns_closed CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL),
    CONSTRAINT ck_grouping_campaigns_cancellation CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL AND length(btrim(cancellation_reason)) > 0)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL AND cancellation_reason IS NULL)),
    CONSTRAINT ck_grouping_campaigns_dates CHECK (updated_at >= created_at
        AND (opened_at IS NULL OR opened_at >= created_at)
        AND (closed_at IS NULL OR (opened_at IS NOT NULL AND closed_at >= opened_at))
        AND (cancelled_at IS NULL OR cancelled_at >= created_at)),
    CONSTRAINT ck_grouping_campaigns_version CHECK (version >= 0)
);
CREATE INDEX ix_grouping_campaigns_organization_status_created ON grouping_campaigns(organization_id, status, created_at DESC, id);
CREATE INDEX ix_grouping_campaigns_organization_created ON grouping_campaigns(organization_id, created_at DESC, id);

CREATE TABLE grouping_offers (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    product_variant_id UUID NOT NULL,
    commercial_label VARCHAR(160),
    unit_price NUMERIC NOT NULL,
    currency VARCHAR(3) NOT NULL,
    price_revision BIGINT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_grouping_offers_campaign_variant UNIQUE (campaign_id, product_variant_id),
    CONSTRAINT uk_grouping_offers_ownership UNIQUE (organization_id, campaign_id, id),
    CONSTRAINT fk_grouping_offers_campaign FOREIGN KEY (organization_id, campaign_id) REFERENCES grouping_campaigns(organization_id, id),
    CONSTRAINT fk_grouping_offers_variant FOREIGN KEY (organization_id, product_variant_id) REFERENCES product_variants(organization_id, id),
    CONSTRAINT ck_grouping_offers_price CHECK (unit_price >= 0 AND unit_price < 1000000000000000000 AND unit_price = trunc(unit_price)),
    CONSTRAINT ck_grouping_offers_currency CHECK (currency = 'XOF'),
    CONSTRAINT ck_grouping_offers_version CHECK (version >= 0 AND price_revision >= 0),
    CONSTRAINT ck_grouping_offers_dates CHECK (updated_at >= created_at)
);
CREATE INDEX ix_grouping_offers_campaign_active ON grouping_offers(campaign_id, active);

CREATE TABLE grouping_changes (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    offer_id UUID,
    actor_id UUID NOT NULL,
    kind VARCHAR(32) NOT NULL,
    campaign_version BIGINT NOT NULL,
    price_revision BIGINT,
    old_price NUMERIC,
    new_price NUMERIC,
    currency VARCHAR(3),
    old_closes_at TIMESTAMP WITH TIME ZONE,
    new_closes_at TIMESTAMP WITH TIME ZONE,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_grouping_changes_campaign FOREIGN KEY (organization_id, campaign_id) REFERENCES grouping_campaigns(organization_id, id),
    CONSTRAINT fk_grouping_changes_offer FOREIGN KEY (organization_id, campaign_id, offer_id) REFERENCES grouping_offers(organization_id, campaign_id, id),
    CONSTRAINT fk_grouping_changes_actor FOREIGN KEY (actor_id) REFERENCES user_accounts(id),
    CONSTRAINT uk_grouping_changes_campaign_revision UNIQUE (campaign_id, campaign_version, kind),
    CONSTRAINT uk_grouping_changes_offer_price_revision UNIQUE (offer_id, price_revision),
    CONSTRAINT ck_grouping_changes_version CHECK (campaign_version >= 0),
    CONSTRAINT ck_grouping_changes_shape CHECK (
        (kind = 'PRICE_CHANGED' AND offer_id IS NOT NULL AND price_revision IS NOT NULL AND price_revision > 0
            AND old_price IS NOT NULL AND new_price IS NOT NULL AND old_price <> new_price
            AND old_price >= 0 AND old_price < 1000000000000000000 AND old_price = trunc(old_price)
            AND new_price >= 0 AND new_price < 1000000000000000000 AND new_price = trunc(new_price)
            AND currency IS NOT NULL AND currency = 'XOF' AND old_closes_at IS NULL AND new_closes_at IS NULL)
        OR (kind = 'DEADLINE_CHANGED' AND offer_id IS NULL AND price_revision IS NULL
            AND old_price IS NULL AND new_price IS NULL AND currency IS NULL
            AND old_closes_at IS DISTINCT FROM new_closes_at))
);
CREATE INDEX ix_grouping_changes_campaign_time ON grouping_changes(campaign_id, occurred_at);
--rollback DROP TABLE grouping_changes;
--rollback DROP TABLE grouping_offers;
--rollback DROP TABLE grouping_campaigns;
--rollback ALTER TABLE product_variants DROP CONSTRAINT uk_variants_organization_id;
