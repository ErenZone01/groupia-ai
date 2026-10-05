--liquibase formatted sql
--changeset groupia:018-distribution-sequence dbms:postgresql splitStatements:false
-- Restore the original 017 checksum and evolve both known schemas additively.
-- The exclusive lock and transactional DDL prevent concurrent journal writes.
DO $$
BEGIN
    LOCK TABLE order_fulfillment_events IN ACCESS EXCLUSIVE MODE;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema=current_schema() AND table_name='order_fulfillment_events'
            AND column_name='recording_sequence'
    ) THEN
        ALTER TABLE order_fulfillment_events ADD COLUMN recording_sequence bigint;
        -- Only new ordering metadata is populated. Historical event fields are unchanged.
        ALTER TABLE order_fulfillment_events DISABLE TRIGGER order_fulfillment_history;
        WITH positions AS (
            SELECT id, row_number() OVER (ORDER BY recorded_at,id) AS sequence
            FROM order_fulfillment_events
        )
        UPDATE order_fulfillment_events e SET recording_sequence=p.sequence
        FROM positions p WHERE e.id=p.id;
        ALTER TABLE order_fulfillment_events ENABLE TRIGGER order_fulfillment_history;
        ALTER TABLE order_fulfillment_events ALTER COLUMN recording_sequence SET NOT NULL;
        ALTER TABLE order_fulfillment_events ALTER COLUMN recording_sequence ADD GENERATED ALWAYS AS IDENTITY;
        ALTER TABLE order_fulfillment_events ADD CONSTRAINT order_fulfillment_events_recording_sequence_key UNIQUE(recording_sequence);
        PERFORM setval(pg_get_serial_sequence('order_fulfillment_events','recording_sequence'),
            COALESCE((SELECT max(recording_sequence) FROM order_fulfillment_events),1),
            EXISTS(SELECT 1 FROM order_fulfillment_events));
    END IF;
END;
$$;
DROP INDEX ix_fulfillment_campaign;
DROP INDEX ix_fulfillment_order;
CREATE INDEX ix_fulfillment_campaign ON order_fulfillment_events(organization_id,campaign_id,recording_sequence);
CREATE INDEX ix_fulfillment_order ON order_fulfillment_events(order_id,recording_sequence);
