--liquibase formatted sql

--changeset groupia:033-direct-orders dbms:postgresql
-- Direct order drafts are structurally independent from GROUPING orders. No payment is
-- requested or reserved at this stage; enablement is controlled by the application.
CREATE SEQUENCE direct_order_number_seq AS BIGINT START WITH 1;

CREATE TABLE direct_orders (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    storefront_id UUID NOT NULL,
    order_number VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    fulfillment_method VARCHAR(16) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    customer_contact_snapshot JSONB NOT NULL,
    delivery_address_snapshot JSONB,
    goods_subtotal_xof BIGINT NOT NULL,
    platform_fee_xof BIGINT NOT NULL,
    platform_fee_rule_snapshot JSONB NOT NULL,
    amount_before_shipping_xof BIGINT NOT NULL,
    shipping_quoted BOOLEAN NOT NULL,
    checkout_idempotency_key VARCHAR(128) NOT NULL,
    checkout_request_fingerprint CHAR(64) NOT NULL,
    placed_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_direct_orders_store FOREIGN KEY (organization_id, storefront_id)
        REFERENCES direct_storefronts(organization_id, id),
    CONSTRAINT uk_direct_orders_tenant_id UNIQUE (organization_id, id),
    CONSTRAINT uk_direct_orders_number UNIQUE (order_number),
    CONSTRAINT uk_direct_orders_idempotency UNIQUE (organization_id, checkout_idempotency_key),
    CONSTRAINT ck_direct_orders_number CHECK (order_number ~ '^DIR-[0-9]{12,19}$'),
    CONSTRAINT ck_direct_orders_status CHECK (status = 'PENDING_PAYMENT_INTEGRATION'),
    CONSTRAINT ck_direct_orders_fulfillment CHECK (fulfillment_method IN ('PICKUP','DELIVERY')),
    CONSTRAINT ck_direct_orders_currency CHECK (currency = 'XOF'),
    CONSTRAINT ck_direct_orders_money CHECK (
        goods_subtotal_xof > 0 AND goods_subtotal_xof <= 99999999999999
        AND platform_fee_xof >= 0 AND platform_fee_xof <= 99999999999999
        AND amount_before_shipping_xof = goods_subtotal_xof + platform_fee_xof),
    CONSTRAINT ck_direct_orders_shipping CHECK (
        (fulfillment_method = 'PICKUP' AND shipping_quoted = true
            AND delivery_address_snapshot IS NULL)
        OR (fulfillment_method = 'DELIVERY' AND shipping_quoted = false
            AND jsonb_typeof(delivery_address_snapshot) = 'object')),
    CONSTRAINT ck_direct_orders_contact CHECK (
        jsonb_typeof(customer_contact_snapshot) = 'object'
        AND customer_contact_snapshot ?& array['firstName','lastName','phoneE164']
        AND jsonb_typeof(platform_fee_rule_snapshot) = 'object'),
    CONSTRAINT ck_direct_orders_key CHECK (
        checkout_idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'
        AND checkout_request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_direct_orders_version CHECK (version >= 0)
);
CREATE INDEX ix_direct_orders_org_placed
    ON direct_orders (organization_id, placed_at DESC, id);
CREATE INDEX ix_direct_orders_org_status_placed
    ON direct_orders (organization_id, status, placed_at DESC, id);

CREATE TABLE direct_order_items (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    order_id UUID NOT NULL,
    offer_id_snapshot UUID NOT NULL,
    product_id_snapshot UUID NOT NULL,
    product_variant_id_snapshot UUID NOT NULL,
    product_name_snapshot VARCHAR(160) NOT NULL,
    variant_attributes_snapshot JSONB NOT NULL,
    sku_snapshot VARCHAR(80),
    sale_unit_snapshot VARCHAR(32),
    line_number INTEGER NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price_xof BIGINT NOT NULL,
    line_total_xof BIGINT NOT NULL,
    price_revision_snapshot BIGINT NOT NULL,
    CONSTRAINT fk_direct_order_items_order FOREIGN KEY (organization_id, order_id)
        REFERENCES direct_orders(organization_id, id),
    CONSTRAINT uk_direct_order_items_line UNIQUE (order_id, line_number),
    CONSTRAINT uk_direct_order_items_offer UNIQUE (order_id, offer_id_snapshot),
    CONSTRAINT ck_direct_order_items_line CHECK (
        line_number BETWEEN 1 AND 20 AND quantity BETWEEN 1 AND 100
        AND unit_price_xof BETWEEN 1 AND 999999999999
        AND line_total_xof = unit_price_xof * quantity
        AND price_revision_snapshot >= 0),
    CONSTRAINT ck_direct_order_items_attributes CHECK (
        jsonb_typeof(variant_attributes_snapshot) = 'object')
);
CREATE INDEX ix_direct_order_items_order ON direct_order_items (order_id, line_number);

--rollback DROP TABLE direct_order_items;
--rollback DROP TABLE direct_orders;
--rollback DROP SEQUENCE direct_order_number_seq;
