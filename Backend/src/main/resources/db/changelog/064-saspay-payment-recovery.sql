--liquibase formatted sql
--changeset groupia:064-saspay-payment-recovery dbms:postgresql
-- Operational state only. No historical payment, webhook, incident or decision is rewritten.
CREATE TABLE saspay_payment_recovery (
    payment_id UUID PRIMARY KEY REFERENCES payments(id),
    next_check_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    claim_token UUID,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_attempt_at TIMESTAMPTZ,
    last_completed_at TIMESTAMPTZ,
    last_result VARCHAR(64),
    CHECK ((lease_until IS NULL) = (claim_token IS NULL))
);
CREATE INDEX ix_saspay_payment_recovery_due ON saspay_payment_recovery(next_check_at, payment_id);
