--liquibase formatted sql
--changeset groupia:051-campaign-cancellation-actor dbms:postgresql splitStatements:false
-- Both known 050 variants converge without replaying cancellation DDL or changing history.
DO $$
BEGIN
    LOCK TABLE grouping_campaigns IN ACCESS EXCLUSIVE MODE;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema=current_schema() AND table_name='grouping_campaigns'
            AND column_name='cancelled_by'
    ) THEN
        ALTER TABLE grouping_campaigns ADD COLUMN cancelled_by UUID;
    ELSIF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema=current_schema() AND table_name='grouping_campaigns'
            AND column_name='cancelled_by' AND udt_schema='pg_catalog'
            AND udt_name='uuid' AND is_nullable='YES' AND column_default IS NULL
    ) THEN
        RAISE EXCEPTION 'Unsupported campaign cancellation actor column';
    END IF;
END;
$$;
