--liquibase formatted sql
--changeset groupia:054-saspay-hosted-settlement-evidence dbms:postgresql
-- Additive migration: preserved Softpay receipts keep their original fee quote snapshots.
-- Hosted checkout confirmations persist exact PSP-verified charges, never guessed quotes.
ALTER TABLE payments
    ADD COLUMN provider_collection_fee NUMERIC,
    ADD COLUMN provider_customer_charged NUMERIC;
ALTER TABLE payments
    ADD CONSTRAINT ck_payment_hosted_fee_amounts
    CHECK (
        (provider_collection_fee IS NULL AND provider_customer_charged IS NULL)
        OR
        (
            provider_collection_fee IS NOT NULL
            AND provider_customer_charged IS NOT NULL
            AND provider_collection_fee >= 0
            AND provider_collection_fee = round(provider_collection_fee, 2)
            AND provider_customer_charged = expected_amount + provider_collection_fee
            AND provider_customer_charged = round(provider_customer_charged, 2)
            AND status = 'SUCCEEDED'
        )
    );
