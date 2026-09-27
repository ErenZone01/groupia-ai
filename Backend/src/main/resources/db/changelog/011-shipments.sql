--liquibase formatted sql
--changeset groupia:011-shipments dbms:postgresql
CREATE TABLE shipments (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    grouping_campaign_id UUID NOT NULL,
    transport_mode VARCHAR(16) NOT NULL CHECK (transport_mode IN ('AIR','SEA','ROAD','OTHER')),
    departed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expected_arrival_at TIMESTAMP WITH TIME ZONE,
    arrived_at TIMESTAMP WITH TIME ZONE,
    tracking_reference VARCHAR(160),
    note VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_shipment_campaign FOREIGN KEY (organization_id,grouping_campaign_id) REFERENCES grouping_campaigns(organization_id,id),
    CONSTRAINT uk_shipment_org_campaign UNIQUE (organization_id,grouping_campaign_id),
    CONSTRAINT ck_shipment_dates CHECK (departed_at <= created_at AND updated_at >= created_at
        AND (expected_arrival_at IS NULL OR expected_arrival_at >= departed_at)
        AND (arrived_at IS NULL OR (arrived_at >= departed_at AND arrived_at <= updated_at)))
);
ALTER TABLE grouping_changes ADD COLUMN from_status VARCHAR(32), ADD COLUMN to_status VARCHAR(32);
ALTER TABLE grouping_changes DROP CONSTRAINT ck_grouping_changes_shape;
ALTER TABLE grouping_changes ADD CONSTRAINT ck_grouping_changes_shape CHECK (((((kind = 'PRICE_CHANGED' AND offer_id IS NOT NULL AND price_revision IS NOT NULL AND price_revision > 0
            AND old_price IS NOT NULL AND new_price IS NOT NULL AND old_price <> new_price
            AND old_price >= 0 AND old_price < 1000000000000000000 AND old_price = trunc(old_price)
            AND new_price >= 0 AND new_price < 1000000000000000000 AND new_price = trunc(new_price)
            AND currency IS NOT NULL AND currency = 'XOF' AND old_closes_at IS NULL AND new_closes_at IS NULL)
        OR (kind = 'DEADLINE_CHANGED' AND offer_id IS NULL AND price_revision IS NULL
            AND old_price IS NULL AND new_price IS NULL AND currency IS NULL
            AND old_closes_at IS DISTINCT FROM new_closes_at)) AND from_status IS NULL AND to_status IS NULL) OR (kind='LOGISTICS_TRANSITION' AND offer_id IS NULL AND price_revision IS NULL AND old_price IS NULL AND new_price IS NULL AND currency IS NULL AND old_closes_at IS NULL AND new_closes_at IS NULL AND ((from_status='SUPPLIER_ORDERED' AND to_status='IN_TRANSIT') OR (from_status='IN_TRANSIT' AND to_status='ARRIVED')))) IS TRUE);

--changeset groupia:011-shipment-history dbms:postgresql splitStatements:false
CREATE FUNCTION protect_shipment_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Shipment history cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF (to_jsonb(NEW) - 'arrived_at' - 'updated_at') IS DISTINCT FROM (to_jsonb(OLD) - 'arrived_at' - 'updated_at')
        OR OLD.arrived_at IS NOT NULL OR NEW.arrived_at IS NULL THEN
        RAISE EXCEPTION 'Shipment departure and first arrival are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

--changeset groupia:011-shipment-trigger dbms:postgresql
CREATE TRIGGER shipment_history_guard BEFORE UPDATE OR DELETE ON shipments
FOR EACH ROW EXECUTE FUNCTION protect_shipment_history();

--changeset groupia:011-logistics-history dbms:postgresql splitStatements:false
CREATE FUNCTION protect_logistics_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.kind='LOGISTICS_TRANSITION' OR (TG_OP='UPDATE' AND NEW.kind='LOGISTICS_TRANSITION') THEN
        RAISE EXCEPTION 'Logistics transition history is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

--changeset groupia:011-logistics-history-trigger dbms:postgresql
CREATE TRIGGER logistics_transition_guard BEFORE UPDATE OR DELETE ON grouping_changes
FOR EACH ROW EXECUTE FUNCTION protect_logistics_transition();
