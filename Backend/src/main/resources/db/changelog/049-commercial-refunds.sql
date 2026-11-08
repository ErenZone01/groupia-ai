--liquibase formatted sql
--changeset groupia:049-commercial-refunds dbms:postgresql
CREATE TABLE commercial_refund_cases (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id),
 target_type VARCHAR(32) NOT NULL CHECK(target_type IN ('ORDER','DIRECT_ORDER')), target_id UUID NOT NULL,
 cancellation_key UUID NOT NULL, expected_order_version BIGINT NOT NULL CHECK(expected_order_version>=0),
 actor_id UUID NOT NULL, reason VARCHAR(500) NOT NULL CHECK(length(btrim(reason))>0),
 currency VARCHAR(3) NOT NULL CHECK(currency='XOF'), collected_principal NUMERIC NOT NULL,
 previously_refunded NUMERIC NOT NULL, amount_due NUMERIC NOT NULL, legacy_platform_fee NUMERIC NOT NULL,
 status VARCHAR(32) NOT NULL CHECK(status IN ('NO_REFUND_DUE','REFUND_DUE','EXTERNALLY_RECORDED')),
 opened_at TIMESTAMPTZ NOT NULL, externally_recorded_at TIMESTAMPTZ, recorded_by UUID,
 declaration_key UUID, declaration_fingerprint VARCHAR(64), external_method VARCHAR(32),
 external_reference VARCHAR(160), note VARCHAR(1000), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(organization_id,cancellation_key), UNIQUE(organization_id,target_type,target_id), UNIQUE(organization_id,id), UNIQUE(organization_id,declaration_key),
 UNIQUE(organization_id,external_method,external_reference),
 CHECK(collected_principal>=0 AND collected_principal=trunc(collected_principal)),
 CHECK(previously_refunded>=0 AND previously_refunded<=collected_principal AND previously_refunded=trunc(previously_refunded)),
 CHECK(amount_due=collected_principal-previously_refunded AND amount_due=trunc(amount_due)),
 CHECK(legacy_platform_fee>=0 AND legacy_platform_fee<=collected_principal),
 CHECK((status='NO_REFUND_DUE')=(amount_due=0)),
 CHECK((status='EXTERNALLY_RECORDED')=(externally_recorded_at IS NOT NULL)),
 CHECK(status<>'EXTERNALLY_RECORDED' OR (recorded_by IS NOT NULL AND declaration_key IS NOT NULL AND declaration_fingerprint IS NOT NULL
  AND external_method IN ('CASH','WAVE','ORANGE_MONEY','BANK_TRANSFER','OTHER')
  AND ((external_method='CASH' AND note IS NOT NULL) OR (external_method<>'CASH' AND external_reference IS NOT NULL))
  AND externally_recorded_at>=opened_at))
);
CREATE TABLE commercial_refund_sources (
 case_id UUID NOT NULL REFERENCES commercial_refund_cases(id), payment_request_id UUID NOT NULL UNIQUE,
 payment_id UUID NOT NULL UNIQUE, provider VARCHAR(64) NOT NULL, provider_account VARCHAR(128) NOT NULL,
 provider_transaction_id VARCHAR(255) NOT NULL, principal NUMERIC NOT NULL CHECK(principal>0),
 currency VARCHAR(3) NOT NULL CHECK(currency='XOF'), PRIMARY KEY(case_id,payment_request_id)
);
CREATE TABLE commercial_refund_events (
 id UUID PRIMARY KEY,case_id UUID NOT NULL REFERENCES commercial_refund_cases(id),
 status VARCHAR(32) NOT NULL,actor_id UUID NOT NULL,occurred_at TIMESTAMPTZ NOT NULL,UNIQUE(case_id,status)
);
CREATE TRIGGER commercial_refund_source_history BEFORE UPDATE OR DELETE ON commercial_refund_sources
 FOR EACH ROW EXECUTE FUNCTION guard_provider_collection_history();
CREATE TRIGGER commercial_refund_event_history BEFORE UPDATE OR DELETE ON commercial_refund_events
 FOR EACH ROW EXECUTE FUNCTION guard_provider_collection_history();
--changeset groupia:049-commercial-refund-guard dbms:postgresql splitStatements:false
CREATE FUNCTION guard_commercial_refund_case() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Commercial refund history is immutable'; END IF;
 IF ROW(NEW.id,NEW.organization_id,NEW.target_type,NEW.target_id,NEW.actor_id,NEW.reason,NEW.cancellation_key,NEW.expected_order_version,NEW.currency,
   NEW.collected_principal,NEW.previously_refunded,NEW.amount_due,NEW.legacy_platform_fee,NEW.opened_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.organization_id,OLD.target_type,OLD.target_id,OLD.actor_id,OLD.reason,OLD.cancellation_key,OLD.expected_order_version,OLD.currency,
   OLD.collected_principal,OLD.previously_refunded,OLD.amount_due,OLD.legacy_platform_fee,OLD.opened_at)
 THEN RAISE EXCEPTION 'Commercial refund amounts and source are immutable'; END IF;
 IF OLD.status IN ('NO_REFUND_DUE','EXTERNALLY_RECORDED') AND NEW IS DISTINCT FROM OLD
 THEN RAISE EXCEPTION 'Recorded commercial refund is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER commercial_refund_case_history BEFORE UPDATE OR DELETE ON commercial_refund_cases
 FOR EACH ROW EXECUTE FUNCTION guard_commercial_refund_case();
