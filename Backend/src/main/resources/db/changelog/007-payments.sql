--liquibase formatted sql

--changeset groupia:007-payments dbms:postgresql
ALTER TABLE orders ADD CONSTRAINT uk_orders_organization_id UNIQUE (organization_id,id);
CREATE TABLE payment_requests (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    order_id UUID NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    business_reference VARCHAR(160) NOT NULL,
    expected_amount NUMERIC NOT NULL,
    goods_amount NUMERIC NOT NULL,
    fee_amount NUMERIC NOT NULL,
    shipping_amount NUMERIC NOT NULL,
    currency VARCHAR(3) NOT NULL,
    acceptance_status VARCHAR(32) NOT NULL,
    settled_payment_id UUID,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_payment_requests_order FOREIGN KEY (organization_id,order_id) REFERENCES orders(organization_id,id),
    CONSTRAINT uk_payment_requests_business_reference UNIQUE (business_reference),
    CONSTRAINT uk_payment_requests_ownership UNIQUE (organization_id,id),
    CONSTRAINT ck_payment_requests_purpose CHECK (purpose IN ('INITIAL_ORDER','SHIPPING_BALANCE')),
    CONSTRAINT ck_payment_requests_currency CHECK (currency='XOF'),
    CONSTRAINT ck_payment_requests_amounts CHECK (expected_amount >= 0 AND expected_amount < 1000000000000000000 AND expected_amount=trunc(expected_amount) AND goods_amount >= 0 AND goods_amount < 1000000000000000000 AND goods_amount=trunc(goods_amount) AND fee_amount >= 0 AND fee_amount < 1000000000000000000 AND fee_amount=trunc(fee_amount) AND shipping_amount >= 0 AND shipping_amount < 1000000000000000000 AND shipping_amount=trunc(shipping_amount)
        AND goods_amount+fee_amount+shipping_amount=expected_amount),
    CONSTRAINT ck_payment_requests_status CHECK (acceptance_status IN ('OPEN','CLOSED','CANCELLED','SUPERSEDED')),
    CONSTRAINT ck_payment_requests_settlement CHECK (
        (acceptance_status='CLOSED' AND settled_payment_id IS NOT NULL AND closed_at IS NOT NULL)
        OR (acceptance_status<>'CLOSED' AND settled_payment_id IS NULL AND closed_at IS NULL)),
    CONSTRAINT ck_payment_requests_cancellation CHECK ((acceptance_status='CANCELLED')=(cancelled_at IS NOT NULL)),
    CONSTRAINT ck_payment_requests_dates CHECK (expires_at>created_at AND version>=0
        AND (closed_at IS NULL OR closed_at>=created_at) AND (cancelled_at IS NULL OR cancelled_at>=created_at))
);
CREATE UNIQUE INDEX uk_payment_requests_initial ON payment_requests(organization_id,order_id) WHERE purpose='INITIAL_ORDER';
CREATE INDEX ix_payment_requests_org_created ON payment_requests(organization_id,created_at DESC,id);
CREATE INDEX ix_payment_requests_org_order ON payment_requests(organization_id,order_id);

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    payment_request_id UUID NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_account_reference VARCHAR(128),
    merchant_reference VARCHAR(80) NOT NULL,
    provider_transaction_id VARCHAR(160),
    provider_idempotency_key VARCHAR(128) NOT NULL,
    initiation_key VARCHAR(128) NOT NULL,
    expected_amount NUMERIC NOT NULL,
    currency VARCHAR(3) NOT NULL,
    confirmed_amount NUMERIC,
    confirmed_currency VARCHAR(3),
    status VARCHAR(32) NOT NULL,
    reconciliation_required BOOLEAN NOT NULL,
    reconciliation_reason VARCHAR(64),
    initiated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    provider_occurred_at TIMESTAMP WITH TIME ZONE,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    last_checked_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_payments_request FOREIGN KEY (organization_id,payment_request_id) REFERENCES payment_requests(organization_id,id),
    CONSTRAINT uk_payments_ownership UNIQUE (organization_id,payment_request_id,id),
    CONSTRAINT uk_payments_org_id UNIQUE (organization_id,id),
    CONSTRAINT uk_payments_merchant UNIQUE (merchant_reference),
    CONSTRAINT uk_payments_initiation UNIQUE (payment_request_id,initiation_key),
    CONSTRAINT uk_payments_provider_key UNIQUE NULLS NOT DISTINCT (provider,provider_account_reference,provider_idempotency_key),
    CONSTRAINT ck_payments_expected CHECK (expected_amount >= 0 AND expected_amount < 1000000000000000000 AND expected_amount=trunc(expected_amount)),
    CONSTRAINT ck_payments_confirmed CHECK (confirmed_amount IS NULL OR (confirmed_amount >= 0 AND confirmed_amount < 1000000000000000000 AND confirmed_amount=trunc(confirmed_amount))),
    CONSTRAINT ck_payments_currency CHECK (currency='XOF'),
    CONSTRAINT ck_payments_status CHECK (status IN ('CREATED','PENDING','SUCCEEDED','FAILED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_payments_success CHECK (
        (status='SUCCEEDED' AND confirmed_amount IS NOT NULL AND confirmed_amount=expected_amount
            AND confirmed_currency IS NOT NULL AND confirmed_currency=currency AND confirmed_at IS NOT NULL AND provider_transaction_id IS NOT NULL)
        OR (status<>'SUCCEEDED' AND confirmed_amount IS NULL AND confirmed_currency IS NULL AND confirmed_at IS NULL)),
    CONSTRAINT ck_payments_review CHECK (reconciliation_required=(reconciliation_reason IS NOT NULL)),
    CONSTRAINT ck_payments_version CHECK (version>=0)
);
CREATE UNIQUE INDEX uk_payments_provider_transaction ON payments(provider,provider_account_reference,provider_transaction_id)
    NULLS NOT DISTINCT WHERE provider_transaction_id IS NOT NULL;
CREATE INDEX ix_payments_request_initiated ON payments(payment_request_id,initiated_at,id);
CREATE INDEX ix_payments_review ON payments(reconciliation_required,initiated_at) WHERE reconciliation_required;
ALTER TABLE payment_requests ADD CONSTRAINT fk_payment_requests_settled_payment
    FOREIGN KEY (organization_id,id,settled_payment_id) REFERENCES payments(organization_id,payment_request_id,id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE payment_webhook_events (
    id UUID PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    provider_account_reference VARCHAR(128),
    provider_event_id VARCHAR(160),
    deduplication_key VARCHAR(64) NOT NULL,
    organization_id UUID,
    payment_id UUID,
    authenticity_verification_result VARCHAR(16) NOT NULL,
    payload_fingerprint VARCHAR(64) NOT NULL,
    processing_status VARCHAR(32) NOT NULL,
    observation JSONB,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_webhooks_payment FOREIGN KEY (organization_id,payment_id) REFERENCES payments(organization_id,id),
    CONSTRAINT uk_webhooks_dedup UNIQUE (deduplication_key),
    CONSTRAINT ck_webhooks_resolution CHECK ((organization_id IS NULL)=(payment_id IS NULL)),
    CONSTRAINT ck_webhooks_auth CHECK (authenticity_verification_result IN ('VALID','INVALID')),
    CONSTRAINT ck_webhooks_status CHECK (processing_status IN ('RECEIVED','PROCESSED','IGNORED','REVIEW_REQUIRED','RETRYABLE_FAILURE')),
    CONSTRAINT ck_webhooks_hash CHECK (payload_fingerprint ~ '^[a-f0-9]{64}$' AND deduplication_key ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_webhooks_attempts CHECK (attempt_count>=0 AND version>=0),
    CONSTRAINT ck_webhooks_observation CHECK (observation IS NULL OR jsonb_typeof(observation)='object')
);
CREATE UNIQUE INDEX uk_webhooks_provider_event ON payment_webhook_events(provider,provider_account_reference,provider_event_id)
    NULLS NOT DISTINCT WHERE provider_event_id IS NOT NULL;
CREATE INDEX ix_webhooks_retry ON payment_webhook_events(processing_status,next_attempt_at);
CREATE INDEX ix_webhooks_payment ON payment_webhook_events(payment_id,received_at);
CREATE INDEX ix_webhooks_org_received ON payment_webhook_events(organization_id,received_at);
CREATE INDEX ix_orders_expiration ON orders(payment_deadline,id) WHERE status='PENDING_PAYMENT';

-- Existing slice-four orders receive the same immutable initial obligation; no provider call.
INSERT INTO payment_requests(id,organization_id,order_id,purpose,business_reference,expected_amount,currency,
    goods_amount,fee_amount,shipping_amount,acceptance_status,expires_at,created_at,cancelled_at,version)
SELECT gen_random_uuid(),organization_id,id,'INITIAL_ORDER','INITIAL_ORDER:'||id::text,
    initial_amount_due,currency,goods_subtotal,platform_fee_amount,initial_shipping_charge_amount,
    CASE WHEN status='PENDING_PAYMENT' THEN 'OPEN' ELSE 'CANCELLED' END,payment_deadline,placed_at,
    CASE WHEN status='PENDING_PAYMENT' THEN NULL ELSE current_timestamp END,0
FROM orders;

--rollback DROP TABLE payment_webhook_events;
--rollback ALTER TABLE payment_requests DROP CONSTRAINT fk_payment_requests_settled_payment;
--rollback DROP TABLE payments;
--rollback DROP TABLE payment_requests;
--rollback DROP INDEX ix_orders_expiration;
--rollback ALTER TABLE orders DROP CONSTRAINT uk_orders_organization_id;
