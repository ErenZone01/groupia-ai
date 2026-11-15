--liquibase formatted sql
--changeset groupia:053-saspay-collection-fee-precision
-- Preserve immutable collection receipts while supporting exact SasPay XOF cents.
-- Principal and seller principal remain integer XOF; only provider charges may be fractional.
ALTER TABLE provider_collections
    DROP CONSTRAINT provider_collections_collection_fee_check;
ALTER TABLE provider_collections
    ADD CONSTRAINT provider_collections_collection_fee_precision_check
    CHECK (collection_fee IS NULL OR
           (collection_fee >= 0 AND collection_fee = round(collection_fee, 2)));
ALTER TABLE provider_collections
    ADD CONSTRAINT provider_collections_customer_charged_precision_check
    CHECK (customer_charged IS NULL OR
           (customer_charged >= 0 AND customer_charged = round(customer_charged, 2)));
