--liquibase formatted sql
--changeset groupia:015-shipping-evidence-shape dbms:postgresql
-- Applies the missing guard to the known early 014 schema, and validates existing rows.
-- On the later 014 schema this named guard is equivalent to its existing anonymous guard.
-- No business data or stored Liquibase checksum is rewritten.
ALTER TABLE shipping_calculations ADD CONSTRAINT ck_shipping_calculation_evidence_shape CHECK (
    jsonb_typeof(explanation)='object'
    AND jsonb_exists_all(explanation, ARRAY['origin','currency','amount','roundingRule','source'])
    AND jsonb_typeof(explanation->'amount')='number'
    AND jsonb_typeof(explanation->'origin')='string'
    AND jsonb_typeof(explanation->'currency')='string'
    AND jsonb_typeof(explanation->'roundingRule')='string'
    AND jsonb_typeof(explanation->'source')='string'
);
