--liquibase formatted sql
--changeset groupia:019-shipping-credit-refunds dbms:postgresql
CREATE TABLE payment_refunds (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    order_id uuid NOT NULL,
    calculation_id uuid NOT NULL,
    amount numeric NOT NULL CHECK(amount>0 AND amount=trunc(amount)),
    currency varchar(3) NOT NULL CHECK(currency='XOF'),
    purpose varchar(32) NOT NULL CHECK(purpose='SHIPPING_CREDIT'),
    method varchar(32) NOT NULL CHECK(method IN ('BICTORYS_PAYOUT','MANUAL_EXTERNAL')),
    external_method varchar(32) NOT NULL CHECK(external_method IN ('WAVE','ORANGE_MONEY','CASH','BANK_TRANSFER','OTHER')),
    status varchar(32) NOT NULL CHECK(status IN ('CREATED','PENDING','SUCCEEDED','FAILED','CANCELLED','REVIEW_REQUIRED')),
    business_reference varchar(200) NOT NULL UNIQUE,
    merchant_reference varchar(80) NOT NULL UNIQUE,
    provider varchar(32) NOT NULL,
    provider_account_reference varchar(128) NOT NULL,
    provider_transaction_id varchar(160),
    provider_idempotency_key uuid NOT NULL UNIQUE,
    recipient_phone_snapshot varchar(24) NOT NULL,
    recipient_name_snapshot varchar(240) NOT NULL,
    initiated_by uuid NOT NULL,
    initiated_at timestamptz NOT NULL,
    provider_occurred_at timestamptz,
    completed_at timestamptz,
    reconciliation_required boolean NOT NULL,
    reconciliation_reason varchar(64),
    manual_reference varchar(160),
    note varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_refund_order UNIQUE(organization_id,order_id),
    CONSTRAINT ck_refund_completion CHECK((status='SUCCEEDED')=(completed_at IS NOT NULL)),
    CONSTRAINT ck_refund_provenance CHECK(
        (method='BICTORYS_PAYOUT' AND provider='BICTORYS' AND provider_account_reference='sandbox'
            AND external_method IN ('WAVE','ORANGE_MONEY') AND recipient_phone_snapshot ~ '^\+221[0-9]{9}$'
            AND manual_reference IS NULL)
        OR (method='MANUAL_EXTERNAL' AND provider='MANUAL_EXTERNAL' AND provider_account_reference='internal'
            AND provider_transaction_id IS NULL
            AND ((external_method='CASH' AND note IS NOT NULL AND length(btrim(note))>0)
                OR (external_method<>'CASH' AND manual_reference IS NOT NULL AND length(btrim(manual_reference))>0)))
    ),
    CONSTRAINT ck_refund_success CHECK(status<>'SUCCEEDED' OR (NOT reconciliation_required AND
        (method='MANUAL_EXTERNAL' OR provider_transaction_id IS NOT NULL)))
);
CREATE UNIQUE INDEX uk_refund_provider_transaction ON payment_refunds(provider,provider_account_reference,provider_transaction_id) WHERE provider_transaction_id IS NOT NULL;
CREATE INDEX ix_refund_review ON payment_refunds(organization_id,initiated_at,id) WHERE reconciliation_required OR status IN ('CREATED','PENDING','REVIEW_REQUIRED');

CREATE TABLE refund_webhook_receipts (
    id uuid PRIMARY KEY,
    fingerprint varchar(64) NOT NULL,
    authentic boolean NOT NULL,
    observation jsonb,
    refund_id uuid REFERENCES payment_refunds(id),
    processing_status varchar(32) NOT NULL CHECK(processing_status IN ('PROCESSED','REVIEW_REQUIRED')),
    last_error_code varchar(64),
    received_at timestamptz NOT NULL,
    UNIQUE(fingerprint,authentic)
);
CREATE INDEX ix_refund_receipt_review ON refund_webhook_receipts(processing_status,received_at);

--changeset groupia:019-refund-history-guard dbms:postgresql splitStatements:false
CREATE FUNCTION protect_payment_refund_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Refund history cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF OLD.status='SUCCEEDED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Successful refund is immutable' USING ERRCODE='23514';
    END IF;
    IF (to_jsonb(NEW)-ARRAY['status','provider_transaction_id','provider_occurred_at','completed_at','reconciliation_required','reconciliation_reason','version'])
        IS DISTINCT FROM
       (to_jsonb(OLD)-ARRAY['status','provider_transaction_id','provider_occurred_at','completed_at','reconciliation_required','reconciliation_reason','version']) THEN
        RAISE EXCEPTION 'Refund financial and recipient snapshots are immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.provider_transaction_id IS NOT NULL AND NEW.provider_transaction_id IS DISTINCT FROM OLD.provider_transaction_id THEN
        RAISE EXCEPTION 'Refund provider transaction is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

--changeset groupia:019-refund-history-trigger dbms:postgresql
CREATE TRIGGER payment_refund_history BEFORE UPDATE OR DELETE ON payment_refunds
    FOR EACH ROW EXECUTE FUNCTION protect_payment_refund_history();

