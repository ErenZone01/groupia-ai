--liquibase formatted sql
--changeset groupia:063-saspay-stable-local-signal-fingerprints dbms:postgresql
-- Operational state only. Historical incident rows and financial tables are unchanged.
-- Fingerprints are derived from safe workflow statuses and entity versions, not PII.
CREATE TABLE saspay_audit_local_signal_states (
    provider_account_reference VARCHAR(128) NOT NULL,
    incident_code VARCHAR(64) NOT NULL,
    reference_fingerprint CHAR(64) NOT NULL,
    source_state_fingerprint CHAR(64) NOT NULL,
    last_changed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (provider_account_reference, incident_code, reference_fingerprint),
    CONSTRAINT ck_saspay_local_signal_account
        CHECK (provider_account_reference ~ '^[0-9a-f-]{36}$'),
    CONSTRAINT ck_saspay_local_signal_code
        CHECK (incident_code ~ '^[A-Z_]{3,64}$'),
    CONSTRAINT ck_saspay_local_signal_reference
        CHECK (reference_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_saspay_local_signal_state
        CHECK (source_state_fingerprint ~ '^[a-f0-9]{64}$')
);

--changeset groupia:063-saspay-stable-local-signal-guard dbms:postgresql splitStatements:false
CREATE FUNCTION guard_saspay_local_signal_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SasPay monitor signal state cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.provider_account_reference IS DISTINCT FROM OLD.provider_account_reference
       OR NEW.incident_code IS DISTINCT FROM OLD.incident_code
       OR NEW.reference_fingerprint IS DISTINCT FROM OLD.reference_fingerprint
       OR NEW.source_state_fingerprint IS NOT DISTINCT FROM OLD.source_state_fingerprint
       OR NEW.last_changed_at < OLD.last_changed_at THEN
        RAISE EXCEPTION 'SasPay monitor signal state must change monotonically'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
--changeset groupia:063-saspay-stable-local-signal-trigger dbms:postgresql
CREATE TRIGGER saspay_local_signal_state_guard
BEFORE UPDATE OR DELETE ON saspay_audit_local_signal_states
FOR EACH ROW EXECUTE FUNCTION guard_saspay_local_signal_state();
