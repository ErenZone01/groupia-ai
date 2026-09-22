--liquibase formatted sql

--changeset groupia:009-provider-checkout dbms:postgresql
ALTER TABLE payments ADD COLUMN payment_reference VARCHAR(40);
ALTER TABLE payments ADD COLUMN provider_charge_id VARCHAR(160);
ALTER TABLE payments ADD COLUMN checkout_url VARCHAR(4096);
CREATE UNIQUE INDEX uk_payments_provider_charge ON payments(provider,provider_account_reference,provider_charge_id)
    NULLS NOT DISTINCT WHERE provider_charge_id IS NOT NULL;
--rollback DROP INDEX uk_payments_provider_charge;
--rollback ALTER TABLE payments DROP COLUMN checkout_url;
--rollback ALTER TABLE payments DROP COLUMN provider_charge_id;
--rollback ALTER TABLE payments DROP COLUMN payment_reference;
