--liquibase formatted sql
--changeset groupia:057-saspay-company-fee-budget dbms:postgresql
-- Independent, append-only financial ledger for Groupia's OWN fee funding.
-- Nothing in this table transfers money or proves that the provider wallet was funded.
CREATE TABLE saspay_company_fee_events (
    id UUID PRIMARY KEY,
    provider_account_reference VARCHAR(128) NOT NULL,
    event_type VARCHAR(12) NOT NULL CHECK (event_type IN ('FUNDING','RESERVE','SETTLE','RELEASE')),
    payout_id UUID,
    funding_reference VARCHAR(160),
    amount_xof NUMERIC(18,2) NOT NULL CHECK (amount_xof >= 0),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_saspay_company_fee_event_shape CHECK (
        (event_type = 'FUNDING' AND payout_id IS NULL AND funding_reference IS NOT NULL AND amount_xof > 0)
        OR (event_type <> 'FUNDING' AND payout_id IS NOT NULL AND funding_reference IS NULL)
    )
);
CREATE UNIQUE INDEX uk_saspay_company_fee_per_payout_event
    ON saspay_company_fee_events(provider_account_reference,payout_id,event_type)
    WHERE payout_id IS NOT NULL;
CREATE UNIQUE INDEX uk_saspay_company_fee_funding_reference
    ON saspay_company_fee_events(provider_account_reference,funding_reference)
    WHERE event_type = 'FUNDING';
CREATE INDEX ix_saspay_company_fee_budget_account
    ON saspay_company_fee_events(provider_account_reference,event_type);
CREATE TRIGGER saspay_company_fee_events_history BEFORE UPDATE OR DELETE
    ON saspay_company_fee_events FOR EACH ROW
    EXECUTE FUNCTION guard_provider_collection_history();
