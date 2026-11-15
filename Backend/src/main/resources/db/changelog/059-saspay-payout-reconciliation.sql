--liquibase formatted sql
--changeset groupia:059-saspay-payout-reconciliation dbms:postgresql
-- Operational scheduling is separate from immutable financial snapshots.
-- No historical payout, key, reservation or financial status is modified.
CREATE TABLE merchant_payout_verifications (
    payout_id UUID PRIMARY KEY REFERENCES merchant_payouts(id),
    next_verification_at TIMESTAMPTZ NOT NULL,
    claim_token UUID NOT NULL DEFAULT gen_random_uuid()
);
CREATE INDEX ix_merchant_payout_verification ON merchant_payout_verifications(next_verification_at, payout_id);

-- Immutable minimal receipts. Notifications are hints, never financial evidence.
CREATE TABLE payout_status_notifications (
    id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    provider_account_reference VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    authentic BOOLEAN NOT NULL,
    provider_transaction_id UUID,
    error_code VARCHAR(80),
    received_at TIMESTAMPTZ NOT NULL,
    UNIQUE(provider, provider_account_reference, fingerprint, authentic)
);

--changeset groupia:059-payout-notification-history dbms:postgresql splitStatements:false
CREATE FUNCTION guard_payout_notification_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Payout notifications are append-only' USING ERRCODE='23514';
END;
$$;
--changeset groupia:059-payout-notification-trigger dbms:postgresql
CREATE TRIGGER payout_notification_history BEFORE UPDATE OR DELETE ON payout_status_notifications
    FOR EACH ROW EXECUTE FUNCTION guard_payout_notification_history();
