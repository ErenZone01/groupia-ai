--liquibase formatted sql

--changeset groupia:006-ordering dbms:postgresql
CREATE SEQUENCE order_number_seq AS BIGINT START WITH 1;

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone_e164 VARCHAR(16) NOT NULL,
    email VARCHAR(254),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_customers_organization_id UNIQUE (organization_id, id),
    CONSTRAINT ck_customers_names CHECK (length(btrim(first_name)) > 0 AND length(btrim(last_name)) > 0),
    CONSTRAINT ck_customers_phone CHECK (phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT ck_customers_dates CHECK (updated_at >= created_at AND version >= 0)
);

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    order_number VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    fulfillment_status VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    customer_contact_snapshot JSONB NOT NULL,
    goods_subtotal NUMERIC NOT NULL,
    platform_fee_amount NUMERIC NOT NULL,
    platform_fee_rule_snapshot JSONB NOT NULL,
    shipping_terms_snapshot JSONB NOT NULL,
    estimated_shipping_amount NUMERIC,
    initial_shipping_charge_amount NUMERIC NOT NULL,
    initial_amount_due NUMERIC NOT NULL,
    checkout_idempotency_key VARCHAR(128) NOT NULL,
    checkout_request_fingerprint VARCHAR(64) NOT NULL,
    tracking_token_hash VARCHAR(64) NOT NULL,
    payment_deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    tracking_token_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    placed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    tracking_token_revoked_at TIMESTAMP WITH TIME ZONE,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    picked_up_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_orders_campaign FOREIGN KEY (organization_id, campaign_id) REFERENCES grouping_campaigns(organization_id, id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (organization_id, customer_id) REFERENCES customers(organization_id, id),
    CONSTRAINT uk_orders_ownership UNIQUE (organization_id, campaign_id, id),
    CONSTRAINT uk_orders_number UNIQUE (order_number),
    CONSTRAINT uk_orders_idempotency UNIQUE (organization_id, checkout_idempotency_key),
    CONSTRAINT uk_orders_tracking_hash UNIQUE (tracking_token_hash),
    CONSTRAINT ck_orders_number CHECK (order_number ~ '^GRP-[0-9]{12,19}$'),
    CONSTRAINT ck_orders_key CHECK (checkout_idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_orders_hashes CHECK (tracking_token_hash ~ '^[a-f0-9]{64}$' AND checkout_request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_orders_currency CHECK (currency = 'XOF'),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING_PAYMENT','CONFIRMED','EXPIRED','CANCELLED','COMPLETED')),
    CONSTRAINT ck_orders_fulfillment CHECK (fulfillment_status IN ('NOT_READY','READY_FOR_PICKUP','PICKED_UP','CANCELLED')),
    CONSTRAINT ck_orders_money CHECK (goods_subtotal >= 0 AND goods_subtotal < 1000000000000000000 AND goods_subtotal = trunc(goods_subtotal)
        AND platform_fee_amount >= 0 AND platform_fee_amount < 1000000000000000000 AND platform_fee_amount = trunc(platform_fee_amount)
        AND initial_shipping_charge_amount >= 0 AND initial_shipping_charge_amount < 1000000000000000000 AND initial_shipping_charge_amount = trunc(initial_shipping_charge_amount)
        AND initial_amount_due >= 0 AND initial_amount_due < 1000000000000000000 AND initial_amount_due = trunc(initial_amount_due)
        AND (estimated_shipping_amount IS NULL OR (estimated_shipping_amount >= 0 AND estimated_shipping_amount < 1000000000000000000 AND estimated_shipping_amount = trunc(estimated_shipping_amount)))),
    CONSTRAINT ck_orders_total CHECK (initial_amount_due = goods_subtotal + platform_fee_amount + initial_shipping_charge_amount),
    CONSTRAINT ck_orders_snapshots CHECK (jsonb_typeof(customer_contact_snapshot) = 'object'
        AND jsonb_typeof(platform_fee_rule_snapshot) = 'object' AND jsonb_typeof(shipping_terms_snapshot) = 'object'
        AND jsonb_exists_all(customer_contact_snapshot, array['firstName','lastName','phoneE164'])
        AND jsonb_exists_all(platform_fee_rule_snapshot, array['type','currency','amount','version'])
        AND jsonb_exists_all(shipping_terms_snapshot, array['shippingCollectionMode','shippingPricingMethod','shippingRate','shippingCurrency','roundingRule'])),
    CONSTRAINT ck_orders_snapshot_currency CHECK (coalesce(platform_fee_rule_snapshot->>'currency' = currency, false)
        AND coalesce(shipping_terms_snapshot->>'shippingCurrency' = currency, false)),
    CONSTRAINT ck_orders_shipping CHECK (
        (shipping_terms_snapshot->>'shippingCollectionMode' = 'AFTER_FINAL_ASSESSMENT' AND initial_shipping_charge_amount = 0)
        OR (shipping_terms_snapshot->>'shippingCollectionMode' = 'ESTIMATED_AT_CHECKOUT'
            AND estimated_shipping_amount IS NOT NULL AND initial_shipping_charge_amount = estimated_shipping_amount)),
    CONSTRAINT ck_orders_dates CHECK (payment_deadline > placed_at AND tracking_token_expires_at > placed_at AND version >= 0
        AND (tracking_token_revoked_at IS NULL OR tracking_token_revoked_at >= placed_at)
        AND (confirmed_at IS NULL OR confirmed_at >= placed_at)
        AND (cancelled_at IS NULL OR cancelled_at >= placed_at)
        AND (completed_at IS NULL OR completed_at >= placed_at)
        AND (picked_up_at IS NULL OR picked_up_at >= placed_at))
);
CREATE INDEX ix_orders_org_campaign_status_placed ON orders(organization_id, campaign_id, status, placed_at DESC, id);
CREATE INDEX ix_orders_org_status_placed ON orders(organization_id, status, placed_at DESC, id);
CREATE INDEX ix_orders_org_placed ON orders(organization_id, placed_at DESC, id);
CREATE INDEX ix_orders_customer ON orders(customer_id);

CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    grouping_offer_id UUID NOT NULL,
    line_number INTEGER NOT NULL,
    product_id_snapshot UUID NOT NULL,
    product_variant_id_snapshot UUID NOT NULL,
    product_name_snapshot VARCHAR(160) NOT NULL,
    product_description_snapshot VARCHAR(5000),
    commercial_label_snapshot VARCHAR(160),
    variant_attributes_snapshot JSONB NOT NULL,
    sku_snapshot VARCHAR(80),
    sale_unit_snapshot VARCHAR(32) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price_snapshot NUMERIC NOT NULL,
    estimated_unit_weight_kg_snapshot NUMERIC,
    estimated_unit_volume_m3_snapshot NUMERIC,
    line_total NUMERIC NOT NULL,
    price_revision_snapshot BIGINT NOT NULL,
    order_id UUID NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (organization_id, campaign_id, order_id) REFERENCES orders(organization_id, campaign_id, id),
    CONSTRAINT fk_order_items_offer FOREIGN KEY (organization_id, campaign_id, grouping_offer_id) REFERENCES grouping_offers(organization_id, campaign_id, id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (organization_id, product_id_snapshot) REFERENCES products(organization_id, id),
    CONSTRAINT fk_order_items_variant FOREIGN KEY (organization_id, product_variant_id_snapshot) REFERENCES product_variants(organization_id, id),
    CONSTRAINT uk_order_items_line UNIQUE (order_id, line_number),
    CONSTRAINT uk_order_items_offer UNIQUE (order_id, grouping_offer_id),
    CONSTRAINT ck_order_items_quantity CHECK (quantity BETWEEN 1 AND 1000000 AND line_number BETWEEN 1 AND 100 AND price_revision_snapshot >= 0),
    CONSTRAINT ck_order_items_money CHECK (unit_price_snapshot >= 0 AND unit_price_snapshot < 1000000000000000000 AND unit_price_snapshot = trunc(unit_price_snapshot) AND line_total >= 0 AND line_total < 1000000000000000000 AND line_total = trunc(line_total)
        AND line_total = unit_price_snapshot * quantity),
    CONSTRAINT ck_order_items_attributes CHECK (jsonb_typeof(variant_attributes_snapshot) = 'object'),
    CONSTRAINT ck_order_items_estimates CHECK (
        (estimated_unit_weight_kg_snapshot IS NULL OR (estimated_unit_weight_kg_snapshot > 0 AND estimated_unit_weight_kg_snapshot < 1000000000000000000))
        AND (estimated_unit_volume_m3_snapshot IS NULL OR (estimated_unit_volume_m3_snapshot > 0 AND estimated_unit_volume_m3_snapshot < 1000000000000000000)))
);
CREATE INDEX ix_order_items_offer ON order_items(grouping_offer_id);
CREATE INDEX ix_order_items_product ON order_items(product_id_snapshot);
CREATE INDEX ix_order_items_variant ON order_items(product_variant_id_snapshot);
--rollback DROP TABLE order_items;
--rollback DROP TABLE orders;
--rollback DROP TABLE customers;
--rollback DROP SEQUENCE order_number_seq;
