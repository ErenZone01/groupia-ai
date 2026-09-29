--liquibase formatted sql
--changeset groupia:012-customer-access dbms:postgresql
-- Existing customers.phone_e164 is canonical and intentionally non-unique.
CREATE INDEX ix_customers_phone_ownership ON customers(phone_e164,organization_id,id);
CREATE INDEX ix_orders_customer_date ON orders(customer_id,organization_id,placed_at DESC,id);
ALTER TABLE orders ADD CONSTRAINT uk_orders_org_id UNIQUE(organization_id,id);
CREATE TABLE order_access_links (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL, order_id UUID NOT NULL, generation BIGINT NOT NULL CHECK(generation>0),
 created_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ, expires_at TIMESTAMPTZ, last_used_at TIMESTAMPTZ,
 FOREIGN KEY(organization_id,order_id) REFERENCES orders(organization_id,id),
 UNIQUE(order_id,generation),
 CHECK(revoked_at IS NULL OR revoked_at>=created_at),
 CHECK(expires_at IS NULL OR expires_at>=created_at),
 CHECK(last_used_at IS NULL OR last_used_at>=created_at)
);
CREATE UNIQUE INDEX uk_order_access_active ON order_access_links(order_id) WHERE revoked_at IS NULL;
CREATE TABLE otp_challenges (
 id UUID PRIMARY KEY, phone_normalized VARCHAR(16) NOT NULL CHECK(phone_normalized ~ '^\+[1-9][0-9]{6,14}$'),
 purpose VARCHAR(32) NOT NULL CHECK(purpose='RECOVER_ORDERS'), code_hash CHAR(64) NOT NULL CHECK(code_hash ~ '^[a-f0-9]{64}$'),
 created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL, attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
 consumed_at TIMESTAMPTZ, revoked_at TIMESTAMPTZ, resend_after TIMESTAMPTZ NOT NULL,
 CHECK(expires_at>created_at AND resend_after>created_at),
 CHECK(consumed_at IS NULL OR consumed_at>=created_at)
);
CREATE INDEX ix_otp_phone_created ON otp_challenges(phone_normalized,created_at DESC);
CREATE INDEX ix_otp_expiry ON otp_challenges(expires_at);
CREATE TABLE customer_access_sessions (
 id UUID PRIMARY KEY, token_hash CHAR(64) NOT NULL UNIQUE CHECK(token_hash ~ '^[a-f0-9]{64}$'),
 scope VARCHAR(24) NOT NULL CHECK(scope IN ('ORDER_ACCESS','PHONE_VERIFIED')),
 link_id UUID REFERENCES order_access_links(id), phone_normalized VARCHAR(16),
 created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ,
 CHECK(expires_at>created_at),
 CHECK((scope='ORDER_ACCESS' AND link_id IS NOT NULL AND phone_normalized IS NULL)
    OR (scope='PHONE_VERIFIED' AND link_id IS NULL AND phone_normalized ~ '^\+[1-9][0-9]{6,14}$' AND phone_normalized IS NOT NULL))
);
CREATE INDEX ix_customer_session_expiry ON customer_access_sessions(expires_at);
CREATE TABLE customer_access_limits (
 subject_hash CHAR(64) PRIMARY KEY CHECK(subject_hash ~ '^[a-f0-9]{64}$'),
 window_start TIMESTAMPTZ NOT NULL, request_count INTEGER NOT NULL CHECK(request_count>0)
);
CREATE INDEX ix_customer_limits_window ON customer_access_limits(window_start);
