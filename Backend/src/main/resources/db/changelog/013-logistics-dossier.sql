--liquibase formatted sql
--changeset groupia:013-logistics-dossier dbms:postgresql
-- Replace the old guard before additive backfill; all existing business dates are retained.
DROP TRIGGER shipment_history_guard ON shipments;
ALTER TABLE shipments ADD COLUMN provider_name VARCHAR(160),
    ADD COLUMN provider_contact VARCHAR(300),
    ADD COLUMN departure_confirmed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN arrival_confirmed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
UPDATE shipments SET departure_confirmed_at=created_at,
    arrival_confirmed_at=CASE WHEN arrived_at IS NOT NULL THEN updated_at END,
    version=CASE WHEN arrived_at IS NOT NULL THEN 1 ELSE 0 END;
ALTER TABLE shipments ALTER COLUMN departed_at DROP NOT NULL;
ALTER TABLE shipments DROP CONSTRAINT ck_shipment_dates;
ALTER TABLE shipments ADD CONSTRAINT uk_shipment_org_id UNIQUE (organization_id,id),
    ADD CONSTRAINT ck_shipment_dates CHECK (
        updated_at >= created_at AND version >= 0
        AND (departure_confirmed_at IS NULL OR (departure_confirmed_at >= created_at AND departure_confirmed_at <= updated_at))
        AND (departed_at IS NULL OR (departure_confirmed_at IS NOT NULL AND departed_at <= departure_confirmed_at))
        AND (arrival_confirmed_at IS NULL OR (departure_confirmed_at IS NOT NULL AND arrival_confirmed_at >= departure_confirmed_at AND arrival_confirmed_at <= updated_at))
        AND (arrived_at IS NULL OR (arrival_confirmed_at IS NOT NULL AND arrived_at <= arrival_confirmed_at))
        AND (departed_at IS NULL OR arrived_at IS NULL OR arrived_at >= departed_at)
        AND (departed_at IS NULL OR expected_arrival_at IS NULL OR expected_arrival_at >= departed_at));
CREATE TABLE shipment_facts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    shipment_version BIGINT NOT NULL CHECK (shipment_version >= 0),
    type VARCHAR(40) NOT NULL CHECK(type IN ('DOSSIER_UPDATED','DEPARTURE_CONFIRMED','ETA_UPDATED','ARRIVAL_CONFIRMED',
        'FORWARDER_RECEIVED','CUSTOMS_STARTED','CUSTOMS_COMPLETED','AVAILABLE_AT_FORWARDER','COLLECTED_BY_MANAGER','INFORMATION')),
    occurred_at TIMESTAMP WITH TIME ZONE,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source VARCHAR(16) NOT NULL CHECK(source IN ('FORWARDER','SUPPLIER','MANAGER','DOCUMENT','OTHER','UNKNOWN')),
    actor_id UUID,
    note VARCHAR(2000),
    previous_expected_arrival_at TIMESTAMP WITH TIME ZONE,
    expected_arrival_at TIMESTAMP WITH TIME ZONE,
    provider_name VARCHAR(160),
    provider_contact VARCHAR(300),
    tracking_reference VARCHAR(160),
    legacy BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_fact_shipment FOREIGN KEY(organization_id,shipment_id) REFERENCES shipments(organization_id,id),
    CONSTRAINT uk_fact_shipment_version UNIQUE(organization_id,shipment_id,shipment_version),
    CONSTRAINT ck_fact_actor CHECK(actor_id IS NOT NULL OR legacy),
    CONSTRAINT ck_fact_dates CHECK(occurred_at IS NULL OR occurred_at <= recorded_at)
);
CREATE UNIQUE INDEX uk_fact_departure ON shipment_facts(shipment_id) WHERE type='DEPARTURE_CONFIRMED';
CREATE UNIQUE INDEX uk_fact_arrival ON shipment_facts(shipment_id) WHERE type='ARRIVAL_CONFIRMED';
INSERT INTO shipment_facts(id,organization_id,shipment_id,shipment_version,type,occurred_at,recorded_at,source,actor_id,
    note,expected_arrival_at,tracking_reference,legacy)
SELECT gen_random_uuid(),s.organization_id,s.id,0,'DEPARTURE_CONFIRMED',s.departed_at,s.created_at,'UNKNOWN',
    (SELECT c.actor_id FROM grouping_changes c WHERE c.campaign_id=s.grouping_campaign_id AND c.organization_id=s.organization_id
        AND c.kind='LOGISTICS_TRANSITION' AND c.to_status='IN_TRANSIT' ORDER BY c.campaign_version LIMIT 1),
    s.note,s.expected_arrival_at,s.tracking_reference,TRUE FROM shipments s;
INSERT INTO shipment_facts(id,organization_id,shipment_id,shipment_version,type,occurred_at,recorded_at,source,actor_id,legacy)
SELECT gen_random_uuid(),s.organization_id,s.id,1,'ARRIVAL_CONFIRMED',s.arrived_at,s.updated_at,'UNKNOWN',
    (SELECT c.actor_id FROM grouping_changes c WHERE c.campaign_id=s.grouping_campaign_id AND c.organization_id=s.organization_id
        AND c.kind='LOGISTICS_TRANSITION' AND c.to_status='ARRIVED' ORDER BY c.campaign_version LIMIT 1),
    TRUE FROM shipments s WHERE s.arrived_at IS NOT NULL;

--changeset groupia:013-dossier-history-guard dbms:postgresql splitStatements:false
CREATE OR REPLACE FUNCTION protect_shipment_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Shipment history cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF ROW(NEW.id,NEW.organization_id,NEW.grouping_campaign_id,NEW.transport_mode,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.organization_id,OLD.grouping_campaign_id,OLD.transport_mode,OLD.created_at)
        OR NEW.version <> OLD.version+1
        OR NEW.updated_at < OLD.updated_at
        OR (OLD.departure_confirmed_at IS NOT NULL AND
            ROW(NEW.departure_confirmed_at,NEW.departed_at) IS DISTINCT FROM ROW(OLD.departure_confirmed_at,OLD.departed_at))
        OR (OLD.arrival_confirmed_at IS NOT NULL AND
            ROW(NEW.arrival_confirmed_at,NEW.arrived_at) IS DISTINCT FROM ROW(OLD.arrival_confirmed_at,OLD.arrived_at)) THEN
        RAISE EXCEPTION 'Shipment identity and confirmed facts are immutable; revision required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

--changeset groupia:013-fact-history-guard dbms:postgresql splitStatements:false
CREATE FUNCTION protect_shipment_fact() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Logistics facts are append-only' USING ERRCODE='23514';
END;
$$;

--changeset groupia:013-logistics-triggers dbms:postgresql
CREATE TRIGGER shipment_history_guard BEFORE UPDATE OR DELETE ON shipments
    FOR EACH ROW EXECUTE FUNCTION protect_shipment_history();
CREATE TRIGGER shipment_fact_history_guard BEFORE UPDATE OR DELETE ON shipment_facts
    FOR EACH ROW EXECUTE FUNCTION protect_shipment_fact();
