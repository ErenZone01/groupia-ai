--liquibase formatted sql

--changeset groupia:029-product-media dbms:postgresql
CREATE TABLE product_media_galleries (
    product_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (organization_id, product_id),
    FOREIGN KEY (organization_id, product_id) REFERENCES products(organization_id, id)
);
CREATE TABLE product_media (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    product_id UUID NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('UPLOADING','READY','DELETE_PENDING','DELETED')),
    position SMALLINT,
    display_storage_key VARCHAR(300) NOT NULL UNIQUE,
    display_content_type VARCHAR(20) NOT NULL CHECK (display_content_type IN ('image/jpeg','image/png')),
    display_width INTEGER NOT NULL CHECK (display_width > 0),
    display_height INTEGER NOT NULL CHECK (display_height > 0),
    display_size_bytes BIGINT NOT NULL CHECK (display_size_bytes > 0),
    display_sha256 CHAR(64) NOT NULL CHECK (display_sha256 ~ '^[0-9a-f]{64}$'),
    thumbnail_storage_key VARCHAR(300) NOT NULL UNIQUE,
    thumbnail_content_type VARCHAR(20) NOT NULL CHECK (thumbnail_content_type IN ('image/jpeg','image/png')),
    thumbnail_width INTEGER NOT NULL CHECK (thumbnail_width > 0),
    thumbnail_height INTEGER NOT NULL CHECK (thumbnail_height > 0),
    thumbnail_size_bytes BIGINT NOT NULL CHECK (thumbnail_size_bytes > 0),
    thumbnail_sha256 CHAR(64) NOT NULL CHECK (thumbnail_sha256 ~ '^[0-9a-f]{64}$'),
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    processing_version INTEGER NOT NULL CHECK (processing_version > 0),
    upload_expires_at TIMESTAMPTZ NOT NULL,
    next_cleanup_at TIMESTAMPTZ,
    cleanup_attempts INTEGER NOT NULL DEFAULT 0 CHECK (cleanup_attempts >= 0),
    cleanup_token UUID,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_media_gallery FOREIGN KEY (organization_id, product_id)
        REFERENCES product_media_galleries(organization_id, product_id),
    CONSTRAINT uk_media_position UNIQUE (product_id, position) DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT uk_media_idempotency UNIQUE (organization_id, product_id, idempotency_key),
    CONSTRAINT ck_media_position CHECK (
        (state IN ('UPLOADING','READY') AND position IS NOT NULL AND position BETWEEN 0 AND 4)
        OR (state IN ('DELETE_PENDING','DELETED') AND position IS NULL))
);
CREATE INDEX ix_media_gallery ON product_media(organization_id, product_id, state, position);
CREATE INDEX ix_media_cleanup ON product_media(next_cleanup_at) WHERE next_cleanup_at IS NOT NULL;
--rollback DROP TABLE product_media;
--rollback DROP TABLE product_media_galleries;
