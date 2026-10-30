--liquibase formatted sql

--changeset groupia:031-user-profile dbms:postgresql
-- Nullable for accounts registered before profile V3. New registrations require all three.
ALTER TABLE user_accounts
    ADD COLUMN first_name VARCHAR(80),
    ADD COLUMN last_name VARCHAR(80),
    ADD COLUMN birth_date DATE;
ALTER TABLE user_accounts
    ADD CONSTRAINT ck_user_accounts_profile_complete
        CHECK ((first_name IS NULL AND last_name IS NULL AND birth_date IS NULL)
            OR (first_name IS NOT NULL AND last_name IS NOT NULL AND birth_date IS NOT NULL
                AND length(btrim(first_name)) > 0 AND length(btrim(last_name)) > 0)),
    ADD CONSTRAINT ck_user_accounts_birth_date_lower_bound
        CHECK (birth_date IS NULL OR birth_date >= DATE '1900-01-01');
--rollback ALTER TABLE user_accounts DROP CONSTRAINT ck_user_accounts_birth_date_lower_bound, DROP CONSTRAINT ck_user_accounts_profile_complete, DROP COLUMN birth_date, DROP COLUMN last_name, DROP COLUMN first_name;
