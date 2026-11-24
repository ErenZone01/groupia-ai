--liquibase formatted sql
--changeset groupia:065-direct-stock dbms:postgresql
-- Historical orders are not retroactively allocated. The merchant configures the
-- quantity still available, excluding commitments made before stock tracking.
ALTER TABLE direct_offers
    ADD COLUMN stock_limit BIGINT,
    ADD COLUMN stock_committed BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN stock_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_direct_offer_stock CHECK
        (stock_committed >= 0 AND stock_version >= 0
         AND (stock_limit IS NULL OR stock_limit >= stock_committed));
ALTER TABLE direct_order_items
    ADD COLUMN stock_committed BOOLEAN NOT NULL DEFAULT false;
