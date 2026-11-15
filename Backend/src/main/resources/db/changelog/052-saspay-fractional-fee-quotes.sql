--liquibase formatted sql
--changeset groupia:052-saspay-fractional-fee-quotes
-- SasPay verify examples include decimal XOF client fees. Monetary quotes are
-- still bounded to exact hundredths and MUST NOT be silently rounded.
-- Historical sale amounts remain whole XOF; only provider fees can be decimals.
ALTER TABLE payment_fee_quotes DROP CONSTRAINT payment_fee_quotes_fee_check;
ALTER TABLE payment_fee_quotes
    ADD CONSTRAINT payment_fee_quotes_fee_precision_check
        CHECK (fee >= 0 AND fee = round(fee, 2));
