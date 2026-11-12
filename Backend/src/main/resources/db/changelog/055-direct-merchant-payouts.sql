--liquibase formatted sql
--changeset groupia:055-direct-merchant-payouts dbms:postgresql
-- Extend the existing immutable payout registry, preserving every historical campaign payout.
ALTER TABLE merchant_payouts ALTER COLUMN campaign_id DROP NOT NULL;
ALTER TABLE merchant_payouts ADD COLUMN direct_order_id UUID;
ALTER TABLE merchant_payouts ADD CONSTRAINT ck_merchant_payout_exact_target
    CHECK ((campaign_id IS NULL) <> (direct_order_id IS NULL));
ALTER TABLE merchant_payouts ADD CONSTRAINT fk_merchant_payout_direct_order
    FOREIGN KEY (organization_id, direct_order_id) REFERENCES direct_orders(organization_id, id);
CREATE UNIQUE INDEX uk_merchant_payout_active_direct
    ON merchant_payouts(organization_id, direct_order_id)
    WHERE direct_order_id IS NOT NULL AND status IN ('CREATED','PENDING','REVIEW_REQUIRED');

-- The existing merchant_payout_history trigger automatically freezes the new target column.
-- SUCCEEDED direct payouts remain durable, immutable debits in this canonical registry.
-- Campaign ledger entries remain unchanged: no synthetic campaign is invented for direct sales.
