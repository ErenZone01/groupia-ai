--liquibase formatted sql
--changeset groupia:020-merchant-settlement dbms:postgresql
CREATE TABLE merchant_ledger_entries (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL, campaign_id uuid NOT NULL, order_id uuid,
    type varchar(48) NOT NULL CHECK(type IN ('ORDER_COLLECTION','SHIPPING_BALANCE_COLLECTION','PLATFORM_FUNDED_CUSTOMER_REFUND','MERCHANT_PAYOUT')),
    direction varchar(6) NOT NULL CHECK(direction IN ('CREDIT','DEBIT')),
    amount numeric NOT NULL CHECK(amount>=0 AND amount=trunc(amount)),
    currency varchar(3) NOT NULL CHECK(currency='XOF'),
    platform_fee_amount numeric NOT NULL DEFAULT 0 CHECK(platform_fee_amount>=0 AND platform_fee_amount=trunc(platform_fee_amount)),
    business_reference varchar(160) NOT NULL UNIQUE, source_type varchar(32) NOT NULL, source_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE(source_type,source_id),
    CHECK((type IN ('ORDER_COLLECTION','SHIPPING_BALANCE_COLLECTION') AND direction='CREDIT' AND source_type='PAYMENT_REQUEST' AND order_id IS NOT NULL)
       OR (type='PLATFORM_FUNDED_CUSTOMER_REFUND' AND direction='DEBIT' AND source_type='CUSTOMER_REFUND' AND order_id IS NOT NULL AND amount>0)
       OR (type='MERCHANT_PAYOUT' AND direction='DEBIT' AND source_type='MERCHANT_PAYOUT' AND order_id IS NULL AND amount>0)),
    CHECK(type='ORDER_COLLECTION' OR platform_fee_amount=0)
);
CREATE INDEX ix_merchant_ledger_scope ON merchant_ledger_entries(organization_id,campaign_id,recorded_at,id);

CREATE TABLE merchant_payout_destinations (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL,
    method varchar(16) NOT NULL CHECK(method IN ('WAVE','ORANGE_MONEY')),
    phone_e164 varchar(16) NOT NULL CHECK(phone_e164 ~ '^\+221[0-9]{9}$'),
    holder_name varchar(160) NOT NULL CHECK(length(btrim(holder_name))>0),
    status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','DISABLED')),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    created_by uuid NOT NULL, updated_by uuid NOT NULL, version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
    CHECK(updated_at>=created_at), UNIQUE(organization_id,id)
);
CREATE UNIQUE INDEX uk_merchant_destination_active ON merchant_payout_destinations(organization_id) WHERE status='ACTIVE';

CREATE TABLE merchant_payouts (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL, campaign_id uuid NOT NULL,
    amount numeric NOT NULL CHECK(amount>0 AND amount=trunc(amount)), currency varchar(3) NOT NULL CHECK(currency='XOF'),
    status varchar(24) NOT NULL CHECK(status IN ('CREATED','PENDING','SUCCEEDED','FAILED','CANCELLED','REVIEW_REQUIRED')),
    destination_id uuid NOT NULL,
    method varchar(16) NOT NULL CHECK(method IN ('WAVE','ORANGE_MONEY')),
    phone_e164 varchar(16) NOT NULL CHECK(phone_e164 ~ '^\+221[0-9]{9}$'),
    holder_name varchar(160) NOT NULL CHECK(length(btrim(holder_name))>0),
    provider varchar(32) NOT NULL, provider_account_reference varchar(128) NOT NULL,
    business_reference varchar(160) NOT NULL UNIQUE, provider_idempotency_key uuid NOT NULL UNIQUE,
    provider_transaction_id varchar(160), requested_by uuid NOT NULL, requested_at timestamptz NOT NULL,
    provider_occurred_at timestamptz, succeeded_at timestamptz,
    reconciliation_required boolean NOT NULL, reconciliation_reason varchar(64),
    version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
    FOREIGN KEY(organization_id,destination_id) REFERENCES merchant_payout_destinations(organization_id,id),
    CHECK((status='SUCCEEDED')=(succeeded_at IS NOT NULL)),
    CHECK(reconciliation_required=(reconciliation_reason IS NOT NULL)),
    CHECK(status<>'SUCCEEDED' OR (provider_transaction_id IS NOT NULL AND NOT reconciliation_required))
);
CREATE UNIQUE INDEX uk_merchant_payout_active ON merchant_payouts(organization_id,campaign_id) WHERE status IN ('CREATED','PENDING','REVIEW_REQUIRED');
CREATE UNIQUE INDEX uk_merchant_payout_transaction ON merchant_payouts(provider,provider_account_reference,provider_transaction_id) WHERE provider_transaction_id IS NOT NULL;
CREATE INDEX ix_merchant_payout_history ON merchant_payouts(organization_id,requested_at DESC,id);

-- Historic attribution reads the immutable source snapshots; never current catalogue prices.
INSERT INTO merchant_ledger_entries(id,organization_id,campaign_id,order_id,type,direction,amount,currency,
    platform_fee_amount,business_reference,source_type,source_id,recorded_at)
SELECT gen_random_uuid(),r.organization_id,o.campaign_id,o.id,
    CASE r.purpose WHEN 'INITIAL_ORDER' THEN 'ORDER_COLLECTION' ELSE 'SHIPPING_BALANCE_COLLECTION' END,
    'CREDIT',r.goods_amount+r.shipping_amount,r.currency,r.fee_amount,
    'COLLECTION:'||r.id::text,'PAYMENT_REQUEST',r.id,r.closed_at
FROM payment_requests r JOIN orders o ON o.id=r.order_id AND o.organization_id=r.organization_id
JOIN payments p ON p.id=r.settled_payment_id AND p.payment_request_id=r.id AND p.organization_id=r.organization_id
WHERE r.acceptance_status='CLOSED' AND p.status='SUCCEEDED' AND o.status IN ('CONFIRMED','COMPLETED')
    AND p.confirmed_amount=r.expected_amount AND p.confirmed_currency=r.currency
ON CONFLICT(business_reference) DO NOTHING;

-- A boutique-funded MANUAL_EXTERNAL refund is intentionally NEVER debited here.
INSERT INTO merchant_ledger_entries(id,organization_id,campaign_id,order_id,type,direction,amount,currency,
    platform_fee_amount,business_reference,source_type,source_id,recorded_at)
SELECT gen_random_uuid(),r.organization_id,o.campaign_id,o.id,'PLATFORM_FUNDED_CUSTOMER_REFUND','DEBIT',
    r.amount,r.currency,0,'CUSTOMER_REFUND:'||r.id::text,'CUSTOMER_REFUND',r.id,r.completed_at
FROM payment_refunds r JOIN orders o ON o.id=r.order_id AND o.organization_id=r.organization_id
WHERE r.status='SUCCEEDED' AND r.method='BICTORYS_PAYOUT'
ON CONFLICT(business_reference) DO NOTHING;

--changeset groupia:020-merchant-history-guards dbms:postgresql splitStatements:false
CREATE FUNCTION guard_merchant_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR TG_TABLE_NAME='merchant_ledger_entries' THEN
        RAISE EXCEPTION 'Merchant financial history is append-only' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='merchant_payout_destinations' THEN
        IF OLD.status='DISABLED' OR NEW.status<>'DISABLED' OR
            (to_jsonb(NEW)-ARRAY['status','updated_at','updated_by','version'])
             IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','updated_at','updated_by','version']) THEN
            RAISE EXCEPTION 'Create a new destination revision' USING ERRCODE='23514';
        END IF;
    ELSE
        IF OLD.status='SUCCEEDED' AND NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'Merchant payout success is immutable' USING ERRCODE='23514';
        END IF;
        IF (to_jsonb(NEW)-ARRAY['status','provider_transaction_id','provider_occurred_at','succeeded_at','reconciliation_required','reconciliation_reason','version'])
             IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','provider_transaction_id','provider_occurred_at','succeeded_at','reconciliation_required','reconciliation_reason','version'])
           OR (OLD.provider_transaction_id IS NOT NULL AND NEW.provider_transaction_id IS DISTINCT FROM OLD.provider_transaction_id) THEN
            RAISE EXCEPTION 'Merchant payout snapshots are immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
--changeset groupia:020-merchant-history-triggers dbms:postgresql
CREATE TRIGGER merchant_ledger_history BEFORE UPDATE OR DELETE ON merchant_ledger_entries FOR EACH ROW EXECUTE FUNCTION guard_merchant_history();
CREATE TRIGGER merchant_destination_history BEFORE UPDATE OR DELETE ON merchant_payout_destinations FOR EACH ROW EXECUTE FUNCTION guard_merchant_history();
CREATE TRIGGER merchant_payout_history BEFORE UPDATE OR DELETE ON merchant_payouts FOR EACH ROW EXECUTE FUNCTION guard_merchant_history();

