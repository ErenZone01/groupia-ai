--liquibase formatted sql
--changeset groupia:026-subscription-billing dbms:postgresql splitStatements:false
ALTER TABLE organization_subscriptions ADD COLUMN billing_timezone varchar(80);
UPDATE organization_subscriptions s SET billing_timezone=o.timezone FROM organizations o WHERE o.id=s.organization_id;
ALTER TABLE organization_subscriptions ALTER COLUMN billing_timezone SET NOT NULL;
ALTER TABLE organization_subscriptions ADD CONSTRAINT uk_subscription_org_id UNIQUE(organization_id,id);

ALTER TABLE payment_requests ADD COLUMN target_type varchar(32);
ALTER TABLE payment_requests ADD COLUMN target_id uuid;
UPDATE payment_requests SET target_type='ORDER',target_id=order_id;
ALTER TABLE payment_requests ALTER COLUMN target_type SET NOT NULL;
ALTER TABLE payment_requests ALTER COLUMN target_id SET NOT NULL;
ALTER TABLE payment_requests ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE payment_requests DROP CONSTRAINT ck_payment_requests_purpose;
ALTER TABLE payment_requests ADD CONSTRAINT ck_payment_requests_target CHECK (
    (purpose IN ('INITIAL_ORDER','SHIPPING_BALANCE') AND target_type='ORDER' AND order_id IS NOT NULL AND target_id=order_id)
    OR (purpose='SUBSCRIPTION_MONTHLY' AND target_type='SUBSCRIPTION_INVOICE' AND order_id IS NULL));
ALTER TABLE payment_requests DROP CONSTRAINT ck_payment_requests_amounts;
ALTER TABLE payment_requests ADD CONSTRAINT ck_payment_requests_amounts CHECK (
    expected_amount>=0 AND expected_amount<1000000000000000000 AND expected_amount=trunc(expected_amount)
    AND goods_amount>=0 AND goods_amount<1000000000000000000 AND goods_amount=trunc(goods_amount)
    AND fee_amount>=0 AND fee_amount<1000000000000000000 AND fee_amount=trunc(fee_amount)
    AND shipping_amount>=0 AND shipping_amount<1000000000000000000 AND shipping_amount=trunc(shipping_amount)
    AND ((target_type='ORDER' AND goods_amount+fee_amount+shipping_amount=expected_amount)
      OR (target_type='SUBSCRIPTION_INVOICE' AND expected_amount>0 AND goods_amount=0 AND fee_amount=0 AND shipping_amount=0)));
ALTER TABLE payment_requests ADD CONSTRAINT ck_payment_subscription_expiry CHECK (purpose<>'SUBSCRIPTION_MONTHLY' OR expires_at IS NOT NULL);
CREATE UNIQUE INDEX uk_payment_subscription_target ON payment_requests(organization_id,target_type,target_id) WHERE target_type='SUBSCRIPTION_INVOICE';
ALTER TABLE payment_requests ADD CONSTRAINT uk_payment_request_target UNIQUE(organization_id,id,target_type,target_id);

CREATE TABLE subscription_invoices (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    subscription_id uuid NOT NULL,
    billing_period_key varchar(160) NOT NULL,
    reference varchar(40) NOT NULL UNIQUE,
    plan_code varchar(40) NOT NULL CHECK(plan_code='STANDARD_MONTHLY'),
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    billing_timezone varchar(80) NOT NULL,
    amount numeric NOT NULL CHECK(amount>0 AND amount<1000000000000000000 AND amount=trunc(amount)),
    currency varchar(3) NOT NULL CHECK(currency='XOF'),
    billing_interval varchar(16) NOT NULL CHECK(billing_interval='MONTHLY'),
    billing_name varchar(160) NOT NULL CHECK(length(btrim(billing_name))>0),
    billing_phone_e164 varchar(16) NOT NULL CHECK(billing_phone_e164 ~ '^\+221[0-9]{9}$'),
    billing_email varchar(254),
    billing_country varchar(2) NOT NULL CHECK(billing_country='SN'),
    billing_locale varchar(16) NOT NULL CHECK(billing_locale='fr-FR'),
    payment_request_id uuid NOT NULL UNIQUE,
    payment_target_type varchar(32) NOT NULL DEFAULT 'SUBSCRIPTION_INVOICE' CHECK(payment_target_type='SUBSCRIPTION_INVOICE'),
    status varchar(16) NOT NULL CHECK(status IN ('OPEN','PAID','EXPIRED')),
    settled_payment_id uuid,
    paid_at timestamptz,
    creation_key varchar(128) NOT NULL CHECK(creation_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    created_at timestamptz NOT NULL,
    expired_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
    UNIQUE(organization_id,id),
    UNIQUE(subscription_id,billing_period_key),
    UNIQUE(subscription_id,period_start),
    UNIQUE(organization_id,creation_key),
    CONSTRAINT fk_invoice_subscription FOREIGN KEY(organization_id,subscription_id) REFERENCES organization_subscriptions(organization_id,id),
    CONSTRAINT fk_invoice_request FOREIGN KEY(organization_id,payment_request_id,payment_target_type,id)
        REFERENCES payment_requests(organization_id,id,target_type,target_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_invoice_principal FOREIGN KEY(organization_id,payment_request_id,settled_payment_id)
        REFERENCES payments(organization_id,payment_request_id,id),
    CHECK(period_end>period_start AND period_end>created_at),
    CHECK((status='PAID' AND settled_payment_id IS NOT NULL AND paid_at IS NOT NULL AND paid_at<period_end)
       OR (status<>'PAID' AND settled_payment_id IS NULL AND paid_at IS NULL)),
    CHECK((status='EXPIRED')=(expired_at IS NOT NULL)),
    CHECK(expired_at IS NULL OR expired_at>=period_end)
);
CREATE INDEX ix_subscription_invoices_history ON subscription_invoices(organization_id,period_start DESC,id);
CREATE INDEX ix_subscription_invoices_open ON subscription_invoices(subscription_id,period_end) WHERE status='OPEN';

ALTER TABLE subscription_periods ADD COLUMN subscription_invoice_id uuid;
ALTER TABLE subscription_periods ADD COLUMN payment_request_id uuid;
ALTER TABLE subscription_periods ADD COLUMN payment_id uuid;
ALTER TABLE subscription_periods ADD COLUMN provider_account_reference varchar(128);
ALTER TABLE subscription_periods ADD CONSTRAINT ck_subscription_period_links CHECK (
    (subscription_invoice_id IS NULL AND payment_request_id IS NULL AND payment_id IS NULL AND provider_account_reference IS NULL)
    OR (subscription_invoice_id IS NOT NULL AND payment_request_id IS NOT NULL AND payment_id IS NOT NULL));
ALTER TABLE subscription_periods ADD CONSTRAINT fk_subscription_period_invoice FOREIGN KEY(organization_id,subscription_invoice_id) REFERENCES subscription_invoices(organization_id,id);
ALTER TABLE subscription_periods ADD CONSTRAINT fk_subscription_period_payment FOREIGN KEY(organization_id,payment_request_id,payment_id) REFERENCES payments(organization_id,payment_request_id,id);
CREATE UNIQUE INDEX uk_subscription_period_invoice ON subscription_periods(subscription_invoice_id) WHERE subscription_invoice_id IS NOT NULL;
CREATE UNIQUE INDEX uk_subscription_period_request ON subscription_periods(payment_request_id) WHERE payment_request_id IS NOT NULL;
CREATE UNIQUE INDEX uk_subscription_period_payment ON subscription_periods(payment_id) WHERE payment_id IS NOT NULL;
ALTER TABLE subscription_periods DROP CONSTRAINT subscription_periods_provider_provider_transaction_id_key;
ALTER TABLE subscription_periods DROP CONSTRAINT subscription_periods_provider_provider_reference_key;
CREATE UNIQUE INDEX uk_subscription_period_legacy_transaction ON subscription_periods(provider,provider_transaction_id) WHERE subscription_invoice_id IS NULL;
CREATE UNIQUE INDEX uk_subscription_period_legacy_reference ON subscription_periods(provider,provider_reference) WHERE subscription_invoice_id IS NULL;
CREATE UNIQUE INDEX uk_subscription_period_transaction ON subscription_periods(provider,provider_account_reference,provider_transaction_id) NULLS NOT DISTINCT WHERE subscription_invoice_id IS NOT NULL;

CREATE FUNCTION protect_subscription_billing() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='organization_subscriptions' THEN
        IF NEW.billing_timezone IS DISTINCT FROM OLD.billing_timezone THEN
            RAISE EXCEPTION 'Billing timezone is immutable' USING ERRCODE='23514';
        END IF;
    ELSE
        IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Invoice history is immutable' USING ERRCODE='23514'; END IF;
        IF (to_jsonb(NEW)-ARRAY['status','settled_payment_id','paid_at','expired_at','version'])
            IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','settled_payment_id','paid_at','expired_at','version'])
            OR (OLD.status<>'OPEN' AND to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD)) THEN
            RAISE EXCEPTION 'Invoice snapshot is immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER subscription_billing_timezone BEFORE UPDATE ON organization_subscriptions FOR EACH ROW EXECUTE FUNCTION protect_subscription_billing();
-- Older provisioning/backfill SQL can still be replayed: initialize the new calendar field
-- on insert, without ever changing the trial or an existing billing timezone.
CREATE FUNCTION initialize_subscription_billing_timezone() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.billing_timezone IS NULL THEN
        SELECT timezone INTO NEW.billing_timezone FROM organizations WHERE id=NEW.organization_id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER subscription_billing_timezone_insert BEFORE INSERT ON organization_subscriptions FOR EACH ROW EXECUTE FUNCTION initialize_subscription_billing_timezone();
CREATE TRIGGER subscription_invoice_history BEFORE UPDATE OR DELETE ON subscription_invoices FOR EACH ROW EXECUTE FUNCTION protect_subscription_billing();

CREATE FUNCTION validate_subscription_period_invoice() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE i subscription_invoices%ROWTYPE; r payment_requests%ROWTYPE; p payments%ROWTYPE;
BEGIN
    IF NEW.subscription_invoice_id IS NULL THEN RETURN NEW; END IF;
    SELECT * INTO i FROM subscription_invoices WHERE id=NEW.subscription_invoice_id;
    SELECT * INTO r FROM payment_requests WHERE id=NEW.payment_request_id;
    SELECT * INTO p FROM payments WHERE id=NEW.payment_id;
    IF i.id IS NULL OR r.id IS NULL OR p.id IS NULL
        OR i.organization_id IS DISTINCT FROM NEW.organization_id OR i.subscription_id IS DISTINCT FROM NEW.subscription_id
        OR i.payment_request_id IS DISTINCT FROM r.id OR r.settled_payment_id IS DISTINCT FROM p.id
        OR r.acceptance_status IS DISTINCT FROM 'CLOSED' OR p.status IS DISTINCT FROM 'SUCCEEDED'
        OR NEW.amount IS DISTINCT FROM i.amount OR NEW.amount IS DISTINCT FROM r.expected_amount
        OR NEW.currency IS DISTINCT FROM i.currency OR NEW.currency IS DISTINCT FROM p.confirmed_currency
        OR NEW.period_start IS DISTINCT FROM i.period_start OR NEW.period_end IS DISTINCT FROM i.period_end
        OR NEW.plan_code IS DISTINCT FROM i.plan_code OR NEW.billing_interval IS DISTINCT FROM i.billing_interval
        OR NEW.provider IS DISTINCT FROM p.provider OR NEW.provider_account_reference IS DISTINCT FROM p.provider_account_reference
        OR NEW.provider_transaction_id IS DISTINCT FROM p.provider_transaction_id
        OR NEW.provider_reference IS DISTINCT FROM p.payment_reference OR NEW.paid_at IS DISTINCT FROM p.confirmed_at
        OR r.closed_at>=i.period_end THEN
        RAISE EXCEPTION 'Subscription period proof mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER subscription_period_invoice_proof BEFORE INSERT ON subscription_periods FOR EACH ROW EXECUTE FUNCTION validate_subscription_period_invoice();
