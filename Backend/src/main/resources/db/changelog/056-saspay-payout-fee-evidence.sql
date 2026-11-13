--liquibase formatted sql
--changeset groupia:056-saspay-payout-fee-evidence dbms:postgresql
-- Append-only provider fee evidence, separate from seller principal.
-- This is NOT a funded company fee budget: no funding/withdrawal permission is implied.
CREATE TABLE merchant_payout_provider_fees (
    payout_id UUID PRIMARY KEY REFERENCES merchant_payouts(id),
    principal_xof NUMERIC(18,2) NOT NULL CHECK (principal_xof > 0 AND principal_xof = trunc(principal_xof)),
    provider_fee_xof NUMERIC(18,2) NOT NULL CHECK (provider_fee_xof >= 0),
    wallet_debited_xof NUMERIC(18,2) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_merchant_payout_fee_debit
        CHECK (wallet_debited_xof = principal_xof + provider_fee_xof)
);
-- An authenticated success must never be overwritten when provider data changes.
CREATE TRIGGER merchant_payout_provider_fee_history BEFORE UPDATE OR DELETE
    ON merchant_payout_provider_fees FOR EACH ROW
    EXECUTE FUNCTION guard_provider_collection_history();
