--liquibase formatted sql
--changeset groupia:027-outgoing-preflight dbms:postgresql splitStatements:false
DO $$
BEGIN
    IF EXISTS (
        WITH historical AS (
            SELECT provider, provider_account_reference, merchant_reference AS reference,
                   provider_idempotency_key AS idempotency_key, provider_transaction_id AS transaction_id
            FROM payment_refunds WHERE method='BICTORYS_PAYOUT'
            UNION ALL
            SELECT provider, provider_account_reference, business_reference,
                   provider_idempotency_key, provider_transaction_id
            FROM merchant_payouts WHERE provider='BICTORYS'
        ), identities AS (
            SELECT provider, provider_account_reference, 'reference' AS kind, reference AS value FROM historical
            UNION ALL
            SELECT provider, provider_account_reference, 'idempotency', idempotency_key::text FROM historical
            UNION ALL
            SELECT provider, provider_account_reference, 'transaction', transaction_id FROM historical
            WHERE transaction_id IS NOT NULL
        )
        SELECT 1 FROM identities
        GROUP BY provider, provider_account_reference, kind, value HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Outgoing payout historical identity collision; manual investigation required'
            USING ERRCODE='23505';
    END IF;
END;
$$;

--changeset groupia:027-outgoing-payout-registry dbms:postgresql
CREATE TABLE outgoing_provider_operations (
    operation_type varchar(32) NOT NULL CHECK(operation_type IN ('REFUND','MERCHANT_PAYOUT')),
    operation_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provider varchar(32) NOT NULL CHECK(length(btrim(provider))>0),
    provider_account_reference varchar(128) NOT NULL,
    business_reference varchar(200) NOT NULL CHECK(length(business_reference)>0),
    provider_idempotency_key uuid NOT NULL,
    expected_amount numeric NOT NULL CHECK(expected_amount>0 AND expected_amount=trunc(expected_amount)),
    currency varchar(3) NOT NULL CHECK(currency='XOF'),
    method varchar(32) NOT NULL CHECK(method IN ('WAVE','ORANGE_MONEY')),
    created_at timestamptz NOT NULL,
    provider_transaction_id varchar(160),
    PRIMARY KEY(operation_type,operation_id),
    UNIQUE(provider,provider_account_reference,business_reference),
    UNIQUE(provider,provider_account_reference,provider_idempotency_key)
);
CREATE UNIQUE INDEX uk_outgoing_provider_transaction
    ON outgoing_provider_operations(provider,provider_account_reference,provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;
-- The unique constraints intentionally abort the upgrade on ANY historical collision.
-- Never discard or choose between conflicting financial owners.
INSERT INTO outgoing_provider_operations
SELECT 'REFUND',id,organization_id,provider,provider_account_reference,merchant_reference,
       provider_idempotency_key,amount,currency,external_method,initiated_at,provider_transaction_id
FROM payment_refunds WHERE method='BICTORYS_PAYOUT';
INSERT INTO outgoing_provider_operations
SELECT 'MERCHANT_PAYOUT',id,organization_id,provider,provider_account_reference,business_reference,
       provider_idempotency_key,amount,currency,method,requested_at,provider_transaction_id
FROM merchant_payouts WHERE provider='BICTORYS';

-- Keep the historical table and observation intact; both outgoing families use this single journal.
ALTER TABLE refund_webhook_receipts
    ADD COLUMN provider varchar(32) NOT NULL DEFAULT 'BICTORYS',
    ADD COLUMN provider_account_reference varchar(128) NOT NULL DEFAULT 'sandbox',
    ADD COLUMN operation_type varchar(32) CHECK(operation_type IN ('REFUND','MERCHANT_PAYOUT')),
    ADD COLUMN operation_id uuid,
    ADD COLUMN provider_transaction_id varchar(160),
    ADD COLUMN business_reference varchar(200),
    ADD COLUMN normalized_evidence jsonb,
    ADD COLUMN processed_at timestamptz;
ALTER TABLE refund_webhook_receipts DROP CONSTRAINT refund_webhook_receipts_processing_status_check;
ALTER TABLE refund_webhook_receipts ADD CONSTRAINT ck_outgoing_receipt_status
    CHECK(processing_status IN ('PENDING','PROCESSED','REVIEW_REQUIRED'));
UPDATE refund_webhook_receipts SET operation_type='REFUND',operation_id=refund_id
    WHERE refund_id IS NOT NULL;
CREATE INDEX ix_outgoing_receipt_operation ON refund_webhook_receipts(operation_type,operation_id,received_at);

--changeset groupia:027-outgoing-history dbms:postgresql splitStatements:false
CREATE FUNCTION protect_outgoing_operation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Outgoing operation history cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF (to_jsonb(NEW)-'provider_transaction_id') IS DISTINCT FROM (to_jsonb(OLD)-'provider_transaction_id')
       OR (OLD.provider_transaction_id IS NOT NULL AND NEW.provider_transaction_id IS DISTINCT FROM OLD.provider_transaction_id) THEN
        RAISE EXCEPTION 'Outgoing operation identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
--changeset groupia:027-outgoing-trigger dbms:postgresql
CREATE TRIGGER outgoing_operation_history BEFORE UPDATE OR DELETE ON outgoing_provider_operations
    FOR EACH ROW EXECUTE FUNCTION protect_outgoing_operation();
