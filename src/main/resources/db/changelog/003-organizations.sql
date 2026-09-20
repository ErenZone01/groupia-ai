--liquibase formatted sql

--changeset groupia:003-organizations dbms:postgresql
CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    default_currency VARCHAR(3) NOT NULL DEFAULT 'XOF',
    country_code VARCHAR(2) NOT NULL DEFAULT 'SN',
    timezone VARCHAR(64) NOT NULL DEFAULT 'Africa/Dakar',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_organizations_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_organizations_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_organizations_currency CHECK (default_currency = 'XOF'),
    CONSTRAINT ck_organizations_country CHECK (country_code = 'SN'),
    CONSTRAINT ck_organizations_timezone CHECK (timezone = 'Africa/Dakar'),
    CONSTRAINT ck_organizations_dates CHECK (updated_at >= created_at)
);

CREATE TABLE organization_members (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    user_account_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_members_organization FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_members_user_account FOREIGN KEY (user_account_id) REFERENCES user_accounts(id),
    CONSTRAINT uk_members_organization_user UNIQUE (organization_id, user_account_id),
    CONSTRAINT ck_members_role CHECK (role IN ('OWNER', 'MANAGER', 'OPERATOR', 'VIEWER')),
    CONSTRAINT ck_members_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_members_revocation CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_at >= joined_at))
);
CREATE INDEX ix_members_user_status_organization
    ON organization_members(user_account_id, status, organization_id);
--rollback DROP TABLE organization_members;
--rollback DROP TABLE organizations;
