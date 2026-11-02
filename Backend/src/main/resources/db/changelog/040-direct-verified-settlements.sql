--liquibase formatted sql

--changeset groupia:040-direct-verified-settlements dbms:postgresql
-- Verified gateway evidence only. Recording a receipt does not make an order fulfillable.
-- A reversal or a refund requires a separate review workflow before commerce is released.
CREATE TABLE direct_order_verified_settlements (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    organization_id UUID NOT NULL,
    direct_order_id UUID NOT NULL,
    payment_request_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    amount_xof NUMERIC NOT NULL,
    currency VARCHAR(3) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_transaction_id VARCHAR(160) NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_direct_verified_order UNIQUE (organization_id,direct_order_id),
    CONSTRAINT uk_direct_verified_request UNIQUE (organization_id,payment_request_id),
    CONSTRAINT uk_direct_verified_payment UNIQUE (organization_id,payment_id),
    CONSTRAINT fk_direct_verified_order FOREIGN KEY (organization_id,direct_order_id)
        REFERENCES direct_orders(organization_id,id),
    CONSTRAINT fk_direct_verified_request FOREIGN KEY (organization_id,payment_request_id)
        REFERENCES payment_requests(organization_id,id),
    CONSTRAINT fk_direct_verified_payment FOREIGN KEY (organization_id,payment_request_id,payment_id)
        REFERENCES payments(organization_id,payment_request_id,id),
    CONSTRAINT ck_direct_verified_amount CHECK (
        amount_xof > 0 AND amount_xof <= 99999999999999
        AND amount_xof = trunc(amount_xof)),
    CONSTRAINT ck_direct_verified_currency CHECK (currency = 'XOF'),
    CONSTRAINT ck_direct_verified_provider CHECK (
        length(btrim(provider)) > 0 AND length(btrim(provider_transaction_id)) > 0),
    CONSTRAINT ck_direct_verified_dates CHECK (recorded_at >= confirmed_at)
);
CREATE INDEX ix_direct_verified_org_recorded
    ON direct_order_verified_settlements (organization_id,recorded_at DESC,id);

--changeset groupia:040-direct-verified-append-only dbms:postgresql splitStatements:false
CREATE FUNCTION guard_direct_verified_settlement_history() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Direct verified settlement evidence is append-only'
        USING ERRCODE = '23514';
END;
$$;

--changeset groupia:040-direct-verified-trigger dbms:postgresql
CREATE TRIGGER direct_verified_settlement_history
    BEFORE UPDATE OR DELETE ON direct_order_verified_settlements
    FOR EACH ROW EXECUTE FUNCTION guard_direct_verified_settlement_history();

--rollback DROP TRIGGER direct_verified_settlement_history ON direct_order_verified_settlements;
--rollback DROP FUNCTION guard_direct_verified_settlement_history();
--rollback DROP TABLE direct_order_verified_settlements;
