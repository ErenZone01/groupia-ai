--liquibase formatted sql
--changeset groupia:062-saspay-administrative-incident-decisions dbms:postgresql
-- This is an independent append-only incident case-management journal.
-- Financial tables, immutable provider observations and vendor balances are unchanged.
CREATE TABLE saspay_audit_incident_decisions (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES saspay_audit_incidents(id),
    actor_id UUID NOT NULL REFERENCES user_accounts(id),
    action VARCHAR(20) NOT NULL CHECK(action IN ('REVIEW_STARTED','REVIEW_NOTE','CLOSED')),
    evidence_reference VARCHAR(120) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    observed_occurrence_count BIGINT NOT NULL CHECK(observed_occurrence_count > 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_saspay_incident_decision_evidence
      CHECK(evidence_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{4,119}$'),
    CONSTRAINT ck_saspay_incident_decision_reason
      CHECK(length(btrim(reason)) BETWEEN 20 AND 500)
);
CREATE INDEX ix_saspay_incident_decisions_history
    ON saspay_audit_incident_decisions(incident_id, recorded_at DESC, id DESC);

--changeset groupia:062-saspay-decision-immutable-fn dbms:postgresql splitStatements:false
CREATE FUNCTION guard_saspay_incident_decision_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'SasPay incident decisions are append-only' USING ERRCODE='23514';
END;
$$;
--changeset groupia:062-saspay-decision-immutable-trigger dbms:postgresql
CREATE TRIGGER saspay_incident_decision_history
BEFORE UPDATE OR DELETE ON saspay_audit_incident_decisions
FOR EACH ROW EXECUTE FUNCTION guard_saspay_incident_decision_history();
