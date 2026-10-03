--liquibase formatted sql
--changeset groupia:014-shipping-assessments dbms:postgresql
--validCheckSum: 9:055b3c7d7a68b80dc68d25a3ebcf7c71
ALTER TABLE grouping_campaigns ADD COLUMN volumetric_divisor NUMERIC;
ALTER TABLE grouping_campaigns ADD CONSTRAINT ck_campaign_volumetric_divisor CHECK (
    volumetric_divisor IS NULL OR (shipping_pricing_method='PER_KG' AND volumetric_divisor>0
    AND volumetric_divisor<=1000000 AND scale(volumetric_divisor)<=6));
CREATE TABLE shipping_assessments (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    order_id UUID NOT NULL,
    snapshot JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','FINALIZED')),
    version BIGINT NOT NULL CHECK(version>=0),
    current_measurement_id UUID,
    current_final_calculation_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK(updated_at>=created_at),
    UNIQUE(organization_id,order_id),
    FOREIGN KEY(organization_id,campaign_id,order_id) REFERENCES orders(organization_id,campaign_id,id),
    CHECK ((status='FINALIZED')=(current_final_calculation_id IS NOT NULL)),
    CHECK (snapshot->>'organizationId'=organization_id::text AND snapshot->>'campaignId'=campaign_id::text
        AND snapshot->>'orderId'=order_id::text)
);
CREATE INDEX ix_shipping_assessments_campaign ON shipping_assessments(organization_id,campaign_id);
CREATE TABLE shipping_measurements (
    id UUID PRIMARY KEY,
    assessment_id UUID NOT NULL REFERENCES shipping_assessments(id),
    information JSONB NOT NULL,
    actor_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    supersedes_measurement_id UUID,
    UNIQUE(assessment_id,id),
    UNIQUE(supersedes_measurement_id),
    FOREIGN KEY(assessment_id,supersedes_measurement_id) REFERENCES shipping_measurements(assessment_id,id),
    CHECK(supersedes_measurement_id IS NULL OR (
        supersedes_measurement_id<>id AND length(trim(information->>'correctionReason'))>0))
);
CREATE INDEX ix_shipping_measurements_history ON shipping_measurements(assessment_id,recorded_at,id);
CREATE TABLE shipping_calculations (
    id UUID PRIMARY KEY,
    assessment_id UUID NOT NULL REFERENCES shipping_assessments(id),
    revision BIGINT NOT NULL CHECK(revision>0),
    kind VARCHAR(8) NOT NULL CHECK(kind='FINAL'),
    measurement_id UUID,
    explanation JSONB NOT NULL,
    actor_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL,
    note VARCHAR(2000),
    supersedes_calculation_id UUID,
    correction_reason VARCHAR(1000),
    UNIQUE(assessment_id,id),
    UNIQUE(assessment_id,revision),
    UNIQUE(supersedes_calculation_id),
    FOREIGN KEY(assessment_id,measurement_id) REFERENCES shipping_measurements(assessment_id,id),
    FOREIGN KEY(assessment_id,supersedes_calculation_id) REFERENCES shipping_calculations(assessment_id,id),
    CHECK(occurred_at IS NULL OR occurred_at<=recorded_at),
    CHECK((revision=1 AND supersedes_calculation_id IS NULL) OR
        (revision>1 AND supersedes_calculation_id IS NOT NULL AND supersedes_calculation_id<>id
         AND correction_reason IS NOT NULL AND length(trim(correction_reason))>0)),
    CHECK(jsonb_typeof(explanation)='object' AND jsonb_exists_all(explanation, ARRAY['origin','currency','amount','roundingRule','source'])
        AND jsonb_typeof(explanation->'amount')='number' AND jsonb_typeof(explanation->'origin')='string'
        AND jsonb_typeof(explanation->'currency')='string' AND jsonb_typeof(explanation->'roundingRule')='string'
        AND jsonb_typeof(explanation->'source')='string'),
    CHECK(explanation->>'origin' IN ('CALCULATED','PROVIDER_QUOTED')),
    CHECK(explanation->>'currency'='XOF' AND explanation->>'roundingRule'='HALF_UP'),
    CHECK((explanation->>'amount')::numeric>=0 AND (explanation->>'amount')::numeric<1000000000000000000
        AND (explanation->>'amount')::numeric=trunc((explanation->>'amount')::numeric)),
    CHECK(explanation->>'source' IN ('FORWARDER','SUPPLIER','MANAGER','DOCUMENT','OTHER','UNKNOWN')),
    CHECK(explanation->>'origin'<>'PROVIDER_QUOTED' OR
        (measurement_id IS NULL AND explanation->>'rate' IS NULL AND explanation->>'billableQuantity' IS NULL))
);
ALTER TABLE shipping_assessments ADD CONSTRAINT fk_assessment_current_measurement
    FOREIGN KEY(id,current_measurement_id) REFERENCES shipping_measurements(assessment_id,id);
ALTER TABLE shipping_assessments ADD CONSTRAINT fk_assessment_current_final
    FOREIGN KEY(id,current_final_calculation_id) REFERENCES shipping_calculations(assessment_id,id);

--changeset groupia:014-shipping-observation-validation dbms:postgresql splitStatements:false
CREATE FUNCTION validate_shipping_measurement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE field text; value numeric;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM jsonb_each(NEW.information) x WHERE x.key IN
        ('actualWeightKg','chargeableWeightKg','volumeCbm','lengthCm','widthCm','heightCm') AND x.value<>'null'::jsonb)
        OR coalesce(NEW.information->>'source','') NOT IN ('FORWARDER','SUPPLIER','MANAGER','DOCUMENT','OTHER','UNKNOWN') THEN
        RAISE EXCEPTION 'A positive measurement and a source are required' USING ERRCODE='23514';
    END IF;
    FOREACH field IN ARRAY ARRAY['actualWeightKg','chargeableWeightKg','volumeCbm','lengthCm','widthCm','heightCm'] LOOP
        value := (NEW.information->>field)::numeric;
        IF value IS NOT NULL AND (value<=0 OR value>=1000000000000 OR scale(value)>18) THEN
            RAISE EXCEPTION 'Invalid physical measurement' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF (NEW.information->>'occurredAt')::timestamptz > NEW.recorded_at
        OR (NEW.information->>'supersedesMeasurementId')::uuid IS DISTINCT FROM NEW.supersedes_measurement_id
        OR (NEW.supersedes_measurement_id IS NOT NULL AND coalesce(length(trim(NEW.information->>'correctionReason')),0)=0) THEN
        RAISE EXCEPTION 'Invalid observation history' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

--changeset groupia:014-shipping-history dbms:postgresql splitStatements:false
CREATE FUNCTION protect_shipping_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME<>'shipping_assessments' OR TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Shipping history is immutable' USING ERRCODE='23514';
    END IF;
    IF ROW(NEW.id,NEW.organization_id,NEW.campaign_id,NEW.order_id,NEW.snapshot,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.organization_id,OLD.campaign_id,OLD.order_id,OLD.snapshot,OLD.created_at)
        OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at
        OR (OLD.current_final_calculation_id IS NOT NULL AND NEW.current_final_calculation_id IS NULL) THEN
        RAISE EXCEPTION 'Shipping snapshot is immutable; version required' USING ERRCODE='23514';
    END IF;
    IF NEW.current_measurement_id IS DISTINCT FROM OLD.current_measurement_id AND NOT EXISTS (
        SELECT 1 FROM shipping_measurements m WHERE m.id=NEW.current_measurement_id AND m.assessment_id=NEW.id
        AND m.supersedes_measurement_id IS NOT DISTINCT FROM OLD.current_measurement_id) THEN
        RAISE EXCEPTION 'Measurement must extend current history' USING ERRCODE='23514';
    END IF;
    IF NEW.current_final_calculation_id IS DISTINCT FROM OLD.current_final_calculation_id AND NOT EXISTS (
        SELECT 1 FROM shipping_calculations c WHERE c.id=NEW.current_final_calculation_id AND c.assessment_id=NEW.id
        AND c.supersedes_calculation_id IS NOT DISTINCT FROM OLD.current_final_calculation_id
        AND c.revision=coalesce((SELECT revision FROM shipping_calculations WHERE id=OLD.current_final_calculation_id),0)+1) THEN
        RAISE EXCEPTION 'Calculation must extend current history' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

--changeset groupia:014-shipping-triggers dbms:postgresql
CREATE TRIGGER shipping_measurement_validation BEFORE INSERT ON shipping_measurements
    FOR EACH ROW EXECUTE FUNCTION validate_shipping_measurement();
CREATE TRIGGER shipping_assessment_history BEFORE UPDATE OR DELETE ON shipping_assessments
    FOR EACH ROW EXECUTE FUNCTION protect_shipping_history();
CREATE TRIGGER shipping_measurement_history BEFORE UPDATE OR DELETE ON shipping_measurements
    FOR EACH ROW EXECUTE FUNCTION protect_shipping_history();
CREATE TRIGGER shipping_calculation_history BEFORE UPDATE OR DELETE ON shipping_calculations
    FOR EACH ROW EXECUTE FUNCTION protect_shipping_history();
