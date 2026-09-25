--liquibase formatted sql

--changeset groupia:002-identity dbms:postgresql
CREATE TABLE user_accounts (
    id UUID PRIMARY KEY,
    phone_number VARCHAR(16) NOT NULL,
    email VARCHAR(254),
    password_hash VARCHAR(60) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_user_accounts_phone UNIQUE (phone_number),
    CONSTRAINT ck_user_accounts_phone CHECK (phone_number ~ '^\+[1-9][0-9]{1,14}$'),
    CONSTRAINT ck_user_accounts_password_hash CHECK (
        length(password_hash) = 60 AND left(password_hash, 4) IN ('$2a$', '$2b$', '$2y$')),
    CONSTRAINT ck_user_accounts_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_user_accounts_dates CHECK (updated_at >= created_at)
);
--rollback DROP TABLE user_accounts;
