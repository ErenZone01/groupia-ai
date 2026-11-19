--liquibase formatted sql
--changeset groupia:061-saspay-incident-monitoring dbms:postgresql
-- Operational observations only. This migration never changes seller balances,
-- payout reservations, historical payment evidence, or vendor credentials.
CREATE TABLE saspay_audit_checkpoints (
    provider_account_reference VARCHAR(128) PRIMARY KEY,
    completed_until TIMESTAMPTZ,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_failure_code VARCHAR(64),
    CONSTRAINT ck_saspay_audit_lease CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_saspay_audit_account CHECK (provider_account_reference ~ '^[0-9a-f-]{36}$')
);

CREATE TABLE saspay_audit_incidents (
    id UUID PRIMARY KEY,
    provider_account_reference VARCHAR(128) NOT NULL,
    incident_code VARCHAR(64) NOT NULL,
    reference_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    occurrence_count BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uk_saspay_incident UNIQUE (provider_account_reference, incident_code, reference_fingerprint),
    CONSTRAINT ck_saspay_incident_code CHECK (incident_code ~ '^[A-Z_]{3,64}$'),
    CONSTRAINT ck_saspay_incident_fingerprint CHECK (reference_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_saspay_incident_status CHECK (status='OPEN'),
    CONSTRAINT ck_saspay_incident_count CHECK (occurrence_count>0 AND last_seen_at>=first_seen_at)
);
CREATE INDEX ix_saspay_incident_open ON saspay_audit_incidents(status, last_seen_at DESC, id);

--changeset groupia:061-saspay-incident-evidence-guard dbms:postgresql splitStatements:false
CREATE FUNCTION guard_saspay_audit_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'SasPay incident or checkpoint history cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='saspay_audit_incidents' THEN
        IF (to_jsonb(NEW)-ARRAY['last_seen_at','occurrence_count'])
            IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['last_seen_at','occurrence_count'])
            OR NEW.occurrence_count <= OLD.occurrence_count
            OR NEW.last_seen_at < OLD.last_seen_at THEN
            RAISE EXCEPTION 'SasPay incident evidence is immutable' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.provider_account_reference IS DISTINCT FROM OLD.provider_account_reference
            OR (OLD.completed_until IS NOT NULL AND
                (NEW.completed_until IS NULL OR NEW.completed_until < OLD.completed_until)) THEN
            RAISE EXCEPTION 'SasPay audit checkpoint cannot rewind' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
--changeset groupia:061-saspay-incident-evidence-triggers dbms:postgresql
CREATE TRIGGER saspay_incident_evidence BEFORE UPDATE OR DELETE ON saspay_audit_incidents
    FOR EACH ROW EXECUTE FUNCTION guard_saspay_audit_evidence();
CREATE TRIGGER saspay_checkpoint_evidence BEFORE UPDATE OR DELETE ON saspay_audit_checkpoints
    FOR EACH ROW EXECUTE FUNCTION guard_saspay_audit_evidence();
