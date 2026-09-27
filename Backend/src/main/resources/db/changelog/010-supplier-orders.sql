--liquibase formatted sql

--changeset groupia:010-supplier-orders dbms:postgresql
-- One immutable document is one purchase aggregate; JSONB lines preserve historical identities/text.
CREATE TABLE supplier_orders (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    grouping_campaign_id UUID NOT NULL,
    source_campaign_version BIGINT NOT NULL CHECK (source_campaign_version >= 0),
    ordered_by UUID NOT NULL,
    ordered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_order_count BIGINT NOT NULL CHECK (accepted_order_count > 0),
    total_units BIGINT NOT NULL CHECK (total_units > 0),
    goods_amount NUMERIC NOT NULL CHECK (goods_amount >= 0 AND goods_amount < 'Infinity'::numeric AND goods_amount = trunc(goods_amount)),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'XOF'),
    lines JSONB NOT NULL CHECK (jsonb_typeof(lines) = 'array' AND jsonb_array_length(lines) > 0),
    accepted_order_ids UUID[] NOT NULL,
    CONSTRAINT ck_supplier_sources CHECK (cardinality(accepted_order_ids) = accepted_order_count AND array_position(accepted_order_ids,NULL) IS NULL),
    CONSTRAINT fk_supplier_campaign FOREIGN KEY (organization_id,grouping_campaign_id) REFERENCES grouping_campaigns(organization_id,id),
    CONSTRAINT fk_supplier_actor FOREIGN KEY (ordered_by) REFERENCES user_accounts(id),
    CONSTRAINT uk_supplier_org_campaign UNIQUE (organization_id,grouping_campaign_id)
);
CREATE TABLE supplier_order_anomalies (
    supplier_order_id UUID NOT NULL REFERENCES supplier_orders(id),
    customer_order_id UUID NOT NULL REFERENCES orders(id),
    event_id UUID NOT NULL,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (supplier_order_id,customer_order_id)
);
-- The unique ownership index serves every purchase lookup; anomaly PK serves counts and replay deduplication.

--changeset groupia:010-supplier-immutability dbms:postgresql splitStatements:false
CREATE FUNCTION supplier_purchase_is_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Supplier purchase is immutable' USING ERRCODE = '23514';
END;
$$;

--changeset groupia:010-supplier-immutability-trigger dbms:postgresql
CREATE TRIGGER supplier_purchase_immutable BEFORE UPDATE OR DELETE ON supplier_orders
FOR EACH ROW EXECUTE FUNCTION supplier_purchase_is_immutable();

--changeset groupia:010-supplier-line-check dbms:postgresql splitStatements:false
CREATE FUNCTION valid_supplier_purchase_lines(value JSONB, units BIGINT, amount NUMERIC) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN jsonb_typeof(value) <> 'array' THEN false ELSE (
        SELECT coalesce(bool_and(
            jsonb_typeof(l->'snapshot'->'totalQuantity') = 'number'
            AND (l->'snapshot'->>'totalQuantity')::numeric > 0
            AND (l->'snapshot'->>'totalQuantity')::numeric = trunc((l->'snapshot'->>'totalQuantity')::numeric)
            AND jsonb_typeof(l->'snapshot'->'goodsAmount') = 'number'
            AND (l->'snapshot'->>'goodsAmount')::numeric >= 0
            AND (l->'snapshot'->>'goodsAmount')::numeric = trunc((l->'snapshot'->>'goodsAmount')::numeric)
            AND coalesce(l->>'productId','') ~ '^[0-9a-f-]{36}$'
            AND coalesce(l->>'productVariantId','') ~ '^[0-9a-f-]{36}$'
            AND coalesce(l->>'groupingOfferId','') ~ '^[0-9a-f-]{36}$'
        ),false)
        AND sum((l->'snapshot'->>'totalQuantity')::numeric) = units
        AND sum((l->'snapshot'->>'goodsAmount')::numeric) = amount
        FROM jsonb_array_elements(value) l
    ) END
$$;

--changeset groupia:010-supplier-line-constraint dbms:postgresql
ALTER TABLE supplier_orders ADD CONSTRAINT ck_supplier_lines CHECK (valid_supplier_purchase_lines(lines,total_units,goods_amount) IS TRUE);

--changeset groupia:010-supplier-line-null-validation dbms:postgresql splitStatements:false
CREATE OR REPLACE FUNCTION valid_supplier_purchase_lines(value JSONB, units BIGINT, amount NUMERIC) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN jsonb_typeof(value) <> 'array' THEN false ELSE (
        SELECT coalesce(bool_and(coalesce(
            jsonb_typeof(l->'snapshot'->'totalQuantity') = 'number'
            AND (l->'snapshot'->>'totalQuantity')::numeric > 0
            AND (l->'snapshot'->>'totalQuantity')::numeric = trunc((l->'snapshot'->>'totalQuantity')::numeric)
            AND jsonb_typeof(l->'snapshot'->'goodsAmount') = 'number'
            AND (l->'snapshot'->>'goodsAmount')::numeric >= 0
            AND (l->'snapshot'->>'goodsAmount')::numeric = trunc((l->'snapshot'->>'goodsAmount')::numeric)
            AND coalesce(l->>'productId','') ~ '^[0-9a-f-]{36}$'
            AND coalesce(l->>'productVariantId','') ~ '^[0-9a-f-]{36}$'
            AND coalesce(l->>'groupingOfferId','') ~ '^[0-9a-f-]{36}$'
        ,false)),false)
        AND sum((l->'snapshot'->>'totalQuantity')::numeric) = units
        AND sum((l->'snapshot'->>'goodsAmount')::numeric) = amount
        FROM jsonb_array_elements(value) l
    ) END
$$;

