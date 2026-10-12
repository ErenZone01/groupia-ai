--liquibase formatted sql
--changeset groupia:025-grouping-completion dbms:postgresql
ALTER TABLE grouping_campaigns ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE grouping_campaigns ADD CONSTRAINT ck_grouping_completion_date CHECK (
    completed_at IS NULL OR (status='COMPLETED' AND completed_at>=created_at
      AND (opened_at IS NULL OR completed_at>=opened_at)
      AND (closed_at IS NULL OR completed_at>=closed_at)));
ALTER TABLE grouping_changes DROP CONSTRAINT ck_grouping_changes_shape;
ALTER TABLE grouping_changes ADD CONSTRAINT ck_grouping_changes_shape CHECK (
    (((((kind = 'PRICE_CHANGED' AND offer_id IS NOT NULL AND price_revision IS NOT NULL AND price_revision > 0
            AND old_price IS NOT NULL AND new_price IS NOT NULL AND old_price <> new_price
            AND old_price >= 0 AND old_price < 1000000000000000000 AND old_price = trunc(old_price)
            AND new_price >= 0 AND new_price < 1000000000000000000 AND new_price = trunc(new_price)
            AND currency IS NOT NULL AND currency = 'XOF' AND old_closes_at IS NULL AND new_closes_at IS NULL)
        OR (kind = 'DEADLINE_CHANGED' AND offer_id IS NULL AND price_revision IS NULL
            AND old_price IS NULL AND new_price IS NULL AND currency IS NULL
            AND old_closes_at IS DISTINCT FROM new_closes_at)) AND from_status IS NULL AND to_status IS NULL) OR (kind='LOGISTICS_TRANSITION' AND offer_id IS NULL AND price_revision IS NULL AND old_price IS NULL AND new_price IS NULL AND currency IS NULL AND old_closes_at IS NULL AND new_closes_at IS NULL AND ((from_status='SUPPLIER_ORDERED' AND to_status='IN_TRANSIT') OR (from_status='IN_TRANSIT' AND to_status='ARRIVED')))) IS TRUE) OR ((kind='COMPLETION_TRANSITION' AND offer_id IS NULL AND price_revision IS NULL
      AND old_price IS NULL AND new_price IS NULL AND currency IS NULL
      AND old_closes_at IS NULL AND new_closes_at IS NULL
      AND from_status='DISTRIBUTING' AND to_status='COMPLETED') IS TRUE));
CREATE UNIQUE INDEX uk_grouping_completion_history ON grouping_changes(campaign_id) WHERE kind='COMPLETION_TRANSITION';

--changeset groupia:025-completion-guards dbms:postgresql splitStatements:false
CREATE FUNCTION protect_grouping_completion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        IF OLD.status='COMPLETED' AND (NEW.status IS DISTINCT FROM OLD.status OR NEW.completed_at IS DISTINCT FROM OLD.completed_at) THEN
            RAISE EXCEPTION 'Completed campaign is terminal' USING ERRCODE='23514';
        END IF;
        IF OLD.completed_at IS NOT NULL AND NEW.completed_at IS DISTINCT FROM OLD.completed_at THEN
            RAISE EXCEPTION 'Completion date is immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.status='COMPLETED' AND OLD.status<>'COMPLETED' AND (OLD.status<>'DISTRIBUTING' OR NEW.completed_at IS NULL) THEN
            RAISE EXCEPTION 'Completion requires distribution and a server date' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.status='COMPLETED' AND NEW.completed_at IS NULL THEN
        RAISE EXCEPTION 'New completed campaign requires a date' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION protect_completion_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.kind='COMPLETION_TRANSITION' OR (TG_OP='UPDATE' AND NEW.kind='COMPLETION_TRANSITION') THEN
        RAISE EXCEPTION 'Completion history is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

--changeset groupia:025-completion-triggers dbms:postgresql
CREATE TRIGGER grouping_completion_guard BEFORE INSERT OR UPDATE ON grouping_campaigns
FOR EACH ROW EXECUTE FUNCTION protect_grouping_completion();
CREATE TRIGGER grouping_completion_history_guard BEFORE UPDATE OR DELETE ON grouping_changes
FOR EACH ROW EXECUTE FUNCTION protect_completion_history();
