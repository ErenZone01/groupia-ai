--liquibase formatted sql
--changeset groupia:030-business-analytics-index dbms:postgresql
-- Only accepted XOF orders with a settled initial payment contribute to analytics.
CREATE INDEX ix_orders_business_analytics ON orders(organization_id, confirmed_at)
    WHERE status IN ('CONFIRMED', 'COMPLETED')
      AND currency = 'XOF'
      AND confirmed_at IS NOT NULL;
--rollback DROP INDEX ix_orders_business_analytics;
