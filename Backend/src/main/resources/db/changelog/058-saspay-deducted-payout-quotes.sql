--liquibase formatted sql
--changeset groupia:058-saspay-deducted-payout-quotes dbms:postgresql
-- Keep historical ADD_ON fee proofs intact while recording future DEDUCTED payouts.
ALTER TABLE merchant_payout_provider_fees
    ADD COLUMN fee_charge_mode VARCHAR(16) NOT NULL DEFAULT 'ADD_ON',
    ADD COLUMN net_amount_xof NUMERIC(18,2)
        GENERATED ALWAYS AS (
            CASE WHEN fee_charge_mode='DEDUCTED'
                 THEN principal_xof-provider_fee_xof
                 ELSE principal_xof END
        ) STORED;
ALTER TABLE merchant_payout_provider_fees DROP CONSTRAINT ck_merchant_payout_fee_debit;
ALTER TABLE merchant_payout_provider_fees
    ADD CONSTRAINT ck_merchant_payout_fee_modes CHECK (
        (fee_charge_mode='ADD_ON' AND wallet_debited_xof=principal_xof+provider_fee_xof)
        OR
        (fee_charge_mode='DEDUCTED' AND wallet_debited_xof=principal_xof
          AND net_amount_xof > 0)
    );
-- A quote is immutable and bound to one merchant, one destination and one source.
CREATE TABLE merchant_payout_fee_quotes (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    campaign_id UUID,
    direct_order_id UUID,
    destination_id UUID NOT NULL REFERENCES merchant_payout_destinations(id),
    method VARCHAR(32) NOT NULL,
    phone_e164 VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_account_reference VARCHAR(128) NOT NULL,
    gross_amount_xof NUMERIC(18,2) NOT NULL CHECK (gross_amount_xof > 0),
    provider_fee_xof NUMERIC(18,2) NOT NULL CHECK (provider_fee_xof >= 0),
    net_amount_xof NUMERIC(18,2) NOT NULL CHECK (net_amount_xof > 0),
    fee_charge_mode VARCHAR(16) NOT NULL CHECK (fee_charge_mode='DEDUCTED'),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_merchant_payout_quote_target CHECK
        ((campaign_id IS NOT NULL AND direct_order_id IS NULL) OR
         (campaign_id IS NULL AND direct_order_id IS NOT NULL)),
    CONSTRAINT ck_merchant_payout_quote_amount CHECK
        (net_amount_xof = gross_amount_xof - provider_fee_xof)
);
CREATE TABLE merchant_payout_fee_quote_bindings (
    quote_id UUID PRIMARY KEY REFERENCES merchant_payout_fee_quotes(id),
    payout_id UUID NOT NULL UNIQUE REFERENCES merchant_payouts(id)
);
CREATE TRIGGER merchant_payout_fee_quotes_history BEFORE UPDATE OR DELETE
    ON merchant_payout_fee_quotes FOR EACH ROW
    EXECUTE FUNCTION guard_provider_collection_history();
CREATE TRIGGER merchant_payout_fee_quote_bindings_history BEFORE UPDATE OR DELETE
    ON merchant_payout_fee_quote_bindings FOR EACH ROW
    EXECUTE FUNCTION guard_provider_collection_history();
