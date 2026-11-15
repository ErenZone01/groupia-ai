--liquibase formatted sql
--changeset groupia:060-payout-resolution dbms:postgresql
-- No historical row is changed. The only release path is an audited business operation.
ALTER TABLE merchant_payouts DROP CONSTRAINT merchant_payouts_status_check;
ALTER TABLE merchant_payouts ADD CONSTRAINT merchant_payouts_status_check
    CHECK(status IN ('CREATED','PENDING','SUCCEEDED','FAILED','CANCELLED','REVIEW_REQUIRED','NON_EXECUTE'));

CREATE TABLE merchant_payout_administrations (
    id uuid PRIMARY KEY,
    payout_id uuid NOT NULL REFERENCES merchant_payouts(id),
    organization_id uuid NOT NULL,
    action varchar(32) NOT NULL CHECK(action IN ('CLOSE_NON_EXECUTED','INDEPENDENT_PILOT')),
    approval_reference varchar(120) NOT NULL UNIQUE,
    approval_sha256 char(64) NOT NULL,
    evidence_type varchar(40) NOT NULL,
    evidence_reference varchar(120) NOT NULL,
    evidence_sha256 char(64) NOT NULL,
    expected_version bigint NOT NULL,
    actor_id uuid NOT NULL,
    reason varchar(500) NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE(payout_id,action)
);
CREATE TABLE merchant_payout_pilot_grants (
    id uuid PRIMARY KEY REFERENCES merchant_payout_administrations(id),
    organization_id uuid NOT NULL,
    uncertain_payout_id uuid NOT NULL UNIQUE REFERENCES merchant_payouts(id),
    direct_order_id uuid NOT NULL,
    destination_id uuid NOT NULL,
    provider_account varchar(128) NOT NULL,
    amount numeric NOT NULL CHECK(amount=1000),
    expires_at timestamptz NOT NULL,
    FOREIGN KEY(organization_id,direct_order_id) REFERENCES direct_orders(organization_id,id),
    FOREIGN KEY(organization_id,destination_id) REFERENCES merchant_payout_destinations(organization_id,id)
);
CREATE TABLE merchant_payout_pilot_bindings (
    grant_id uuid PRIMARY KEY REFERENCES merchant_payout_pilot_grants(id),
    payout_id uuid NOT NULL UNIQUE REFERENCES merchant_payouts(id)
);
CREATE TABLE merchant_payout_resolution_incidents (
    payout_id uuid PRIMARY KEY REFERENCES merchant_payouts(id),
    reason varchar(64) NOT NULL,
    recorded_at timestamptz NOT NULL
);

--changeset groupia:060-payout-resolution-guard dbms:postgresql splitStatements:false
CREATE FUNCTION guard_payout_resolution() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='NON_EXECUTE' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Non-executed payout is terminal and immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.status='NON_EXECUTE' AND OLD.status<>'NON_EXECUTE' THEN
        IF OLD.status<>'REVIEW_REQUIRED' OR OLD.provider_transaction_id IS NOT NULL
           OR NOT EXISTS (SELECT 1 FROM merchant_payout_administrations a
                WHERE a.payout_id=OLD.id AND a.organization_id=OLD.organization_id
                  AND a.action='CLOSE_NON_EXECUTED' AND a.expected_version=OLD.version) THEN
            RAISE EXCEPTION 'Reviewed non-execution evidence required' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
--changeset groupia:060-payout-resolution-triggers dbms:postgresql
CREATE TRIGGER merchant_payout_resolution_guard BEFORE UPDATE ON merchant_payouts
    FOR EACH ROW EXECUTE FUNCTION guard_payout_resolution();
CREATE TRIGGER merchant_payout_administration_history BEFORE UPDATE OR DELETE ON merchant_payout_administrations
    FOR EACH ROW EXECUTE FUNCTION guard_payout_notification_history();
CREATE TRIGGER merchant_payout_grant_history BEFORE UPDATE OR DELETE ON merchant_payout_pilot_grants
    FOR EACH ROW EXECUTE FUNCTION guard_payout_notification_history();
CREATE TRIGGER merchant_payout_binding_history BEFORE UPDATE OR DELETE ON merchant_payout_pilot_bindings
    FOR EACH ROW EXECUTE FUNCTION guard_payout_notification_history();
CREATE TRIGGER merchant_payout_resolution_incident_history BEFORE UPDATE OR DELETE ON merchant_payout_resolution_incidents
    FOR EACH ROW EXECUTE FUNCTION guard_payout_notification_history();
