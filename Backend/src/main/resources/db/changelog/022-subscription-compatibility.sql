--liquibase formatted sql
--changeset groupia:022-subscription-migration-metadata dbms:postgresql
CREATE TABLE subscription_migration_metadata (
    id smallint PRIMARY KEY CHECK (id = 1),
    applied_at timestamptz NOT NULL
);
INSERT INTO subscription_migration_metadata(id, applied_at)
SELECT 1, COALESCE(
    (SELECT min(trial_started_at)
     FROM organization_subscriptions
     WHERE trial_provenance = 'LEGACY_MIGRATION_TRIAL'),
    transaction_timestamp()
);

--changeset groupia:022-remove-paid-period-creation-time-check dbms:postgresql splitStatements:false
DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
      FROM pg_constraint
     WHERE conrelid = 'subscription_periods'::regclass
       AND contype = 'c'
       AND position('paid_at' in pg_get_constraintdef(oid)) > 0
       AND position('created_at' in pg_get_constraintdef(oid)) > 0;
    IF constraint_name IS NULL THEN
        RAISE EXCEPTION 'Expected the historical paid_at/created_at constraint on subscription_periods';
    END IF;
    EXECUTE format('ALTER TABLE subscription_periods DROP CONSTRAINT %I', constraint_name);
END;
$$;

--changeset groupia:022-subscription-backfill dbms:postgresql
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
       m.applied_at, m.applied_at, owner.user_account_id
FROM organizations o
JOIN subscription_migration_metadata m ON m.id = 1
JOIN LATERAL (
    SELECT user_account_id
    FROM organization_members
    WHERE organization_id = o.id AND status = 'ACTIVE' AND role = 'OWNER'
    ORDER BY joined_at, id
    LIMIT 1
) owner ON true
ON CONFLICT (organization_id) DO NOTHING;
