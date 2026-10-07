--liquibase formatted sql
--changeset groupia:021-subscriptions dbms:postgresql
--validCheckSum: 9:1c621751968700b8e7bb2930af6c2eba
CREATE TABLE organization_subscriptions (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL UNIQUE REFERENCES organizations(id),
    status varchar(20) NOT NULL CHECK (status IN ('TRIALING','ACTIVE','PAST_DUE','SUSPENDED','CANCELED')),
    trial_started_at timestamptz NOT NULL,
    trial_ends_at timestamptz NOT NULL,
    trial_provenance varchar(32) NOT NULL CHECK (trial_provenance IN ('ORGANIZATION_CREATED','LEGACY_MIGRATION_TRIAL')),
    current_period_started_at timestamptz,
    current_period_ends_at timestamptz,
    plan_code varchar(40),
    plan_amount numeric CHECK (plan_amount IS NULL OR (plan_amount > 0 AND plan_amount = trunc(plan_amount))),
    currency varchar(3) NOT NULL DEFAULT 'XOF' CHECK (currency = 'XOF'),
    billing_interval varchar(16) NOT NULL DEFAULT 'MONTHLY' CHECK (billing_interval = 'MONTHLY'),
    provider varchar(32),
    provider_customer_id varchar(160),
    provider_subscription_id varchar(160),
    provider_setup_key uuid UNIQUE,
    provider_setup_requested_at timestamptz,
    cancel_at_period_end boolean NOT NULL DEFAULT false,
    canceled_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (trial_ends_at > trial_started_at),
    CHECK ((current_period_started_at IS NULL) = (current_period_ends_at IS NULL)),
    CHECK (current_period_ends_at IS NULL OR current_period_ends_at > current_period_started_at),
    CHECK ((provider_customer_id IS NULL) = (provider_subscription_id IS NULL)),
    CHECK ((provider_setup_key IS NULL) = (provider_setup_requested_at IS NULL)),
    CHECK (canceled_at IS NULL OR cancel_at_period_end)
);

CREATE TABLE subscription_billing_contacts (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL UNIQUE REFERENCES organizations(id),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    phone_e164 varchar(16),
    email varchar(254),
    country varchar(2) NOT NULL DEFAULT 'SN' CHECK (country = 'SN'),
    locale varchar(16) NOT NULL DEFAULT 'fr-FR' CHECK (locale = 'fr-FR'),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (phone_e164 IS NULL OR phone_e164 ~ '^\+221[0-9]{9}$'),
    CHECK (email IS NULL OR (length(btrim(email)) > 0 AND email = btrim(email))),
    CHECK (updated_at >= created_at)
);

CREATE TABLE subscription_periods (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    subscription_id uuid NOT NULL REFERENCES organization_subscriptions(id),
    plan_code varchar(40) NOT NULL,
    amount numeric NOT NULL CHECK (amount > 0 AND amount = trunc(amount)),
    currency varchar(3) NOT NULL CHECK (currency = 'XOF'),
    billing_interval varchar(16) NOT NULL CHECK (billing_interval = 'MONTHLY'),
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    provider varchar(32) NOT NULL,
    provider_transaction_id varchar(160) NOT NULL,
    provider_reference varchar(160) NOT NULL,
    status varchar(16) NOT NULL CHECK (status = 'PAID'),
    paid_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (provider, provider_transaction_id),
    UNIQUE (provider, provider_reference),
    CHECK (period_end > period_start)
);
CREATE INDEX ix_subscription_period_history
    ON subscription_periods(organization_id, period_start DESC, id);

CREATE TABLE subscription_migration_metadata (
    id smallint PRIMARY KEY CHECK (id = 1),
    applied_at timestamptz NOT NULL
);
INSERT INTO subscription_migration_metadata(id, applied_at)
VALUES (1, transaction_timestamp());

-- Existing organizations receive a clearly attributed migration trial, not a trial
-- backdated to organization.created_at.
INSERT INTO organization_subscriptions (
    id, organization_id, status, trial_started_at, trial_ends_at, trial_provenance,
    created_at, updated_at
)
SELECT gen_random_uuid(), o.id, 'TRIALING', m.applied_at,
       ((m.applied_at AT TIME ZONE o.timezone) + interval '1 month') AT TIME ZONE o.timezone,
       'LEGACY_MIGRATION_TRIAL', m.applied_at, m.applied_at
FROM organizations o
JOIN subscription_migration_metadata m ON m.id = 1
ON CONFLICT (organization_id) DO NOTHING;

INSERT INTO subscription_billing_contacts (
    id, organization_id, name, country, locale, created_at, updated_at, updated_by
)
SELECT gen_random_uuid(), o.id, o.name, o.country_code, 'fr-FR',
       transaction_timestamp(), transaction_timestamp(), m.user_account_id
FROM organizations o
JOIN LATERAL (
    SELECT user_account_id
    FROM organization_members
    WHERE organization_id = o.id AND status = 'ACTIVE' AND role = 'OWNER'
    ORDER BY joined_at, id
    LIMIT 1
) m ON true
ON CONFLICT (organization_id) DO NOTHING;

--changeset groupia:021-subscription-period-history-guard dbms:postgresql splitStatements:false
CREATE FUNCTION guard_subscription_period_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Paid subscription period history is immutable' USING ERRCODE = '23514';
END;
$$;
--changeset groupia:021-subscription-period-history-trigger dbms:postgresql
CREATE TRIGGER subscription_period_history
    BEFORE UPDATE OR DELETE ON subscription_periods
    FOR EACH ROW EXECUTE FUNCTION guard_subscription_period_history();
