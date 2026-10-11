--liquibase formatted sql
--changeset groupia:024-payment-reviews dbms:postgresql splitStatements:false
--validCheckSum: 9:982310b2ff67921dfa159b9ee836d093
CREATE TABLE payment_reviews (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    payment_request_id UUID NOT NULL,
    principal_payment_id UUID NOT NULL,
    anomalous_payment_id UUID NOT NULL,
    reason VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    anomalous_provider VARCHAR(64) NOT NULL,
    anomalous_provider_account_reference VARCHAR(128),
    anomalous_provider_transaction_id VARCHAR(160) NOT NULL,
    observed_amount NUMERIC NOT NULL,
    observed_currency VARCHAR(3) NOT NULL,
    provider_occurred_at TIMESTAMP WITH TIME ZONE,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolved_by UUID,
    external_refund_reference VARCHAR(160),
    resolution_note VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_payment_reviews_request FOREIGN KEY (organization_id,payment_request_id)
        REFERENCES payment_requests(organization_id,id),
    CONSTRAINT fk_payment_reviews_principal FOREIGN KEY (organization_id,payment_request_id,principal_payment_id)
        REFERENCES payments(organization_id,payment_request_id,id),
    CONSTRAINT fk_payment_reviews_attempt FOREIGN KEY (organization_id,payment_request_id,anomalous_payment_id)
        REFERENCES payments(organization_id,payment_request_id,id),
    CONSTRAINT uk_payment_reviews_incident UNIQUE NULLS NOT DISTINCT
        (anomalous_provider,anomalous_provider_account_reference,anomalous_provider_transaction_id,reason),
    CONSTRAINT ck_payment_reviews_reason CHECK (reason='DUPLICATE_PROVIDER_SUCCESS'),
    CONSTRAINT ck_payment_reviews_status CHECK (status IN ('OPEN','RESOLVED_REFUNDED')),
    CONSTRAINT ck_payment_reviews_transaction CHECK (anomalous_provider_transaction_id ~ '^[A-Za-z0-9_-]{1,160}$'),
    CONSTRAINT ck_payment_reviews_amount CHECK (observed_amount>=0 AND observed_amount<1000000000000000000
        AND observed_amount=trunc(observed_amount) AND observed_currency='XOF'),
    CONSTRAINT ck_payment_reviews_resolution CHECK (
        (status='OPEN' AND resolved_at IS NULL AND resolved_by IS NULL AND external_refund_reference IS NULL AND resolution_note IS NULL)
        OR (status='RESOLVED_REFUNDED' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL
            AND external_refund_reference IS NOT NULL AND length(btrim(external_refund_reference))>0)),
    CONSTRAINT ck_payment_reviews_dates CHECK (version>=0 AND (resolved_at IS NULL OR resolved_at>=detected_at))
);
CREATE INDEX ix_payment_reviews_listing ON payment_reviews(organization_id,status,detected_at DESC,id);
CREATE INDEX ix_payment_reviews_request ON payment_reviews(payment_request_id);
CREATE INDEX ix_payment_reviews_open_attempt ON payment_reviews(anomalous_payment_id) WHERE status='OPEN';

CREATE FUNCTION protect_payment_request_principal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.settled_payment_id IS NOT NULL AND NEW.settled_payment_id IS DISTINCT FROM OLD.settled_payment_id THEN
        RAISE EXCEPTION 'Allocated payment principal is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payment_request_principal_immutable BEFORE UPDATE ON payment_requests
    FOR EACH ROW EXECUTE FUNCTION protect_payment_request_principal();

CREATE FUNCTION protect_payment_review_history() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    obligation payment_requests%ROWTYPE;
    principal payments%ROWTYPE;
    attempt payments%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Payment review history cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' THEN
        IF (to_jsonb(NEW)-ARRAY['status','resolved_at','resolved_by','external_refund_reference','resolution_note','version'])
            IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','resolved_at','resolved_by','external_refund_reference','resolution_note','version'])
            OR (OLD.status='RESOLVED_REFUNDED' AND to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD)) THEN
            RAISE EXCEPTION 'Payment review evidence and resolution are immutable' USING ERRCODE='23514';
        END IF;
    ELSE
        SELECT * INTO obligation FROM payment_requests WHERE id=NEW.payment_request_id FOR UPDATE;
        SELECT * INTO principal FROM payments WHERE id=NEW.principal_payment_id;
        SELECT * INTO attempt FROM payments WHERE id=NEW.anomalous_payment_id;
        IF obligation.settled_payment_id IS DISTINCT FROM NEW.principal_payment_id
            OR principal.status IS DISTINCT FROM 'SUCCEEDED'
            OR NEW.anomalous_provider IS DISTINCT FROM attempt.provider
            OR NEW.anomalous_provider_account_reference IS DISTINCT FROM attempt.provider_account_reference
            OR NEW.observed_amount IS DISTINCT FROM attempt.expected_amount
            OR NEW.observed_amount IS DISTINCT FROM obligation.expected_amount
            OR NEW.observed_currency IS DISTINCT FROM attempt.currency
            OR NEW.observed_currency IS DISTINCT FROM obligation.currency
            OR (NEW.anomalous_provider=principal.provider
                AND NEW.anomalous_provider_account_reference IS NOT DISTINCT FROM principal.provider_account_reference
                AND NEW.anomalous_provider_transaction_id=principal.provider_transaction_id) THEN
            RAISE EXCEPTION 'Payment review must describe a distinct debit on an allocated obligation' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payment_review_history BEFORE INSERT OR UPDATE OR DELETE ON payment_reviews
    FOR EACH ROW EXECUTE FUNCTION protect_payment_review_history();

--rollback DROP TRIGGER payment_review_history ON payment_reviews;
--rollback DROP FUNCTION protect_payment_review_history();
--rollback DROP TRIGGER payment_request_principal_immutable ON payment_requests;
--rollback DROP FUNCTION protect_payment_request_principal();
--rollback DROP TABLE payment_reviews;
