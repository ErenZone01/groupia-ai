--liquibase formatted sql
--changeset groupia:047-provider-collection-ledger dbms:postgresql
CREATE TABLE provider_collections (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id),
 payment_request_id UUID NOT NULL UNIQUE, payment_id UUID NOT NULL UNIQUE,
 target_type VARCHAR(32) NOT NULL CHECK(target_type IN ('ORDER','DIRECT_ORDER')), target_id UUID NOT NULL,
 purpose VARCHAR(40) NOT NULL CHECK(purpose IN ('INITIAL_ORDER','SHIPPING_BALANCE','DIRECT_ORDER_INITIAL')),
 provider VARCHAR(64) NOT NULL, provider_account VARCHAR(128) NOT NULL,
 provider_transaction_id VARCHAR(255) NOT NULL, currency VARCHAR(3) NOT NULL CHECK(currency='XOF'),
 principal NUMERIC NOT NULL CHECK(principal>0 AND principal=trunc(principal)),
 seller_principal NUMERIC NOT NULL CHECK(seller_principal>=0 AND seller_principal=trunc(seller_principal)),
 platform_fee NUMERIC NOT NULL CHECK(platform_fee>=0 AND platform_fee=trunc(platform_fee)),
 collection_fee NUMERIC CHECK(collection_fee>=0 AND collection_fee=trunc(collection_fee)),
 customer_charged NUMERIC, confirmed_at TIMESTAMPTZ NOT NULL,
 CHECK(principal=seller_principal+platform_fee),
 CHECK((collection_fee IS NULL AND customer_charged IS NULL) OR
       (collection_fee IS NOT NULL AND customer_charged IS NOT NULL AND customer_charged=principal+collection_fee)),
 UNIQUE(organization_id,id), UNIQUE(provider,provider_account,provider_transaction_id)
);
CREATE INDEX provider_collections_org_idx ON provider_collections(organization_id,id);
CREATE FUNCTION guard_provider_collection_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Provider collection evidence is immutable'; END $$;
CREATE TRIGGER provider_collection_history BEFORE UPDATE OR DELETE ON provider_collections
 FOR EACH ROW EXECUTE FUNCTION guard_provider_collection_history();
-- Forward-only: financial evidence must be retained.
