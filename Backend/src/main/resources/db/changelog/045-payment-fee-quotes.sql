--liquibase formatted sql
--changeset groupia:045-payment-fee-quotes
CREATE TABLE payment_fee_quotes (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id),
    request_id uuid NOT NULL REFERENCES payment_requests(id),
    provider varchar(64) NOT NULL,
    account varchar(128) NOT NULL,
    country varchar(2) NOT NULL,
    network varchar(64) NOT NULL,
    label varchar(120) NOT NULL,
    amount numeric NOT NULL CHECK (amount > 0 AND amount = trunc(amount)),
    fee numeric NOT NULL CHECK (fee >= 0 AND fee = trunc(fee)),
    currency varchar(3) NOT NULL CHECK (currency = 'XOF'),
    expires_at timestamptz NOT NULL
);
CREATE INDEX payment_fee_quotes_request_idx ON payment_fee_quotes(request_id);
ALTER TABLE payments ADD COLUMN fee_quote_id uuid REFERENCES payment_fee_quotes(id);
