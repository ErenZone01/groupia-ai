--liquibase formatted sql
--changeset groupia:017-distribution dbms:postgresql
--validCheckSum: 9:c94ed11d0a9ca499454b514b08148d07
ALTER TABLE orders ADD COLUMN ready_for_pickup_at timestamp with time zone;
CREATE TABLE order_fulfillment_events (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    order_id uuid NOT NULL REFERENCES orders(id),
    type varchar(32) NOT NULL CHECK(type IN ('READY_FOR_PICKUP','READINESS_REVOKED','PICKED_UP')),
    actor_id uuid NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    note varchar(1000),
    CONSTRAINT ck_fulfillment_revocation_reason CHECK(type<>'READINESS_REVOKED' OR (note IS NOT NULL AND length(btrim(note))>0))
);
CREATE INDEX ix_fulfillment_campaign ON order_fulfillment_events(organization_id,campaign_id,recorded_at,id);
CREATE INDEX ix_fulfillment_order ON order_fulfillment_events(order_id,recorded_at,id);
CREATE UNIQUE INDEX uk_fulfillment_pickup ON order_fulfillment_events(order_id) WHERE type='PICKED_UP';
CREATE INDEX ix_orders_distribution ON orders(organization_id,campaign_id,fulfillment_status) WHERE status IN ('CONFIRMED','COMPLETED');

--changeset groupia:017-distribution-history dbms:postgresql splitStatements:false
CREATE FUNCTION protect_order_fulfillment_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'Fulfillment history is immutable' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM orders o WHERE o.id=NEW.order_id AND o.organization_id=NEW.organization_id AND o.campaign_id=NEW.campaign_id) THEN
        RAISE EXCEPTION 'Fulfillment scope mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

--changeset groupia:017-distribution-trigger dbms:postgresql
CREATE TRIGGER order_fulfillment_history BEFORE INSERT OR UPDATE OR DELETE ON order_fulfillment_events
    FOR EACH ROW EXECUTE FUNCTION protect_order_fulfillment_history();
