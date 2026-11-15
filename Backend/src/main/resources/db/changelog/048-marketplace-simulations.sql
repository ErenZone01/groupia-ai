--liquibase formatted sql
--changeset groupia:048-marketplace-simulations dbms:postgresql
CREATE TABLE marketplace_simulations (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id), collection_id UUID NOT NULL,
 FOREIGN KEY(organization_id,collection_id) REFERENCES provider_collections(organization_id,id),
 actor_id UUID NOT NULL, idempotency_key UUID NOT NULL, kind VARCHAR(12) NOT NULL CHECK(kind IN ('PAYOUT','REFUND')),
 mode VARCHAR(16) NOT NULL DEFAULT 'SIMULATION' CHECK(mode='SIMULATION'),
 source_provider VARCHAR(64) NOT NULL, source_account VARCHAR(128) NOT NULL, currency VARCHAR(3) NOT NULL CHECK(currency='XOF'),
 recipient_net NUMERIC NOT NULL CHECK(recipient_net>0 AND recipient_net=trunc(recipient_net)),
 fee NUMERIC NOT NULL CHECK(fee>=0 AND fee=trunc(fee)), quote_reference VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL CHECK(status IN ('CREATED','REVIEW_REQUIRED','SUCCEEDED','FAILED')),
 provider_reference VARCHAR(100), created_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ,
 UNIQUE(organization_id,idempotency_key),
 CHECK((status IN ('SUCCEEDED','FAILED'))=(completed_at IS NOT NULL))
);
CREATE UNIQUE INDEX marketplace_simulation_source_reservation ON marketplace_simulations(collection_id) WHERE status<>'FAILED';
CREATE TABLE marketplace_simulation_events (
 id UUID PRIMARY KEY, operation_id UUID NOT NULL REFERENCES marketplace_simulations(id),
 status VARCHAR(24) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL, UNIQUE(operation_id,status)
);
CREATE TRIGGER marketplace_simulation_event_history BEFORE UPDATE OR DELETE ON marketplace_simulation_events
 FOR EACH ROW EXECUTE FUNCTION guard_provider_collection_history();
CREATE TABLE marketplace_simulated_provider_receipts (
 operation_id UUID PRIMARY KEY REFERENCES marketplace_simulations(id), fingerprint VARCHAR(64) NOT NULL,
 reference VARCHAR(100) NOT NULL UNIQUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER marketplace_simulated_receipt_history BEFORE UPDATE OR DELETE ON marketplace_simulated_provider_receipts
 FOR EACH ROW EXECUTE FUNCTION guard_provider_collection_history();
-- These tables never mutate real payout/refund/order balances. Forward-only audit history.

--changeset groupia:048-marketplace-intent-guard dbms:postgresql splitStatements:false
CREATE FUNCTION guard_marketplace_simulation_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Simulation audit cannot be deleted'; END IF;
 IF ROW(NEW.id,NEW.organization_id,NEW.collection_id,NEW.actor_id,NEW.idempotency_key,NEW.kind,NEW.mode,
        NEW.source_provider,NEW.source_account,NEW.currency,NEW.recipient_net,NEW.fee,NEW.quote_reference,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.id,OLD.organization_id,OLD.collection_id,OLD.actor_id,OLD.idempotency_key,OLD.kind,OLD.mode,
        OLD.source_provider,OLD.source_account,OLD.currency,OLD.recipient_net,OLD.fee,OLD.quote_reference,OLD.created_at)
 THEN RAISE EXCEPTION 'Simulation intent and net are immutable'; END IF;
 IF OLD.status IN ('SUCCEEDED','FAILED') AND NEW IS DISTINCT FROM OLD
 THEN RAISE EXCEPTION 'Terminal simulation cannot regress'; END IF;
 IF OLD.status='REVIEW_REQUIRED' AND NEW.status='CREATED'
 THEN RAISE EXCEPTION 'Simulation cannot regress'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER marketplace_simulation_intent BEFORE UPDATE OR DELETE ON marketplace_simulations
 FOR EACH ROW EXECUTE FUNCTION guard_marketplace_simulation_intent();
