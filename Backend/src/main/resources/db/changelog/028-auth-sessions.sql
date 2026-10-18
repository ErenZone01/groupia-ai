--liquibase formatted sql
--changeset groupia:028-auth-sessions dbms:postgresql splitStatements:false
CREATE TABLE auth_sessions (
 id uuid PRIMARY KEY,
 user_account_id uuid NOT NULL REFERENCES user_accounts(id),
 created_at timestamptz NOT NULL,
 authenticated_at timestamptz NOT NULL,
 last_used_at timestamptz NOT NULL,
 absolute_expires_at timestamptz NOT NULL,
 idle_expires_at timestamptz NOT NULL,
 revoked_at timestamptz,
 revocation_reason varchar(24),
 refresh_limit_key bytea NOT NULL UNIQUE CHECK (octet_length(refresh_limit_key)=32),
 refresh_window_started_at timestamptz NOT NULL,
 refresh_attempts integer NOT NULL DEFAULT 0 CHECK (refresh_attempts>=0),
 CHECK (authenticated_at=created_at AND last_used_at>=created_at AND absolute_expires_at>created_at),
 CHECK (idle_expires_at>created_at AND idle_expires_at<=absolute_expires_at),
 CHECK (refresh_window_started_at>=created_at),
 CHECK ((revoked_at IS NULL AND revocation_reason IS NULL) OR
  (revoked_at IS NOT NULL AND revocation_reason IS NOT NULL AND revoked_at>=created_at AND revocation_reason IN ('LOGOUT','REUSED','EXPIRED','ACCOUNT_DISABLED','REPLACED')))
);
CREATE INDEX ix_auth_sessions_user ON auth_sessions(user_account_id,id);
CREATE INDEX ix_auth_sessions_expiry ON auth_sessions(absolute_expires_at,id);
CREATE TABLE auth_refresh_tokens (
 session_id uuid NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
 generation bigint NOT NULL CHECK (generation>=1),
 token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash)=32),
 issued_at timestamptz NOT NULL,
 consumed_at timestamptz,
 PRIMARY KEY (session_id,generation),
 CHECK (consumed_at IS NULL OR consumed_at>=issued_at)
);
CREATE UNIQUE INDEX uk_auth_refresh_current ON auth_refresh_tokens(session_id) WHERE consumed_at IS NULL;
-- Immutable family identity, lifetime and consumed-token evidence.
CREATE FUNCTION guard_auth_session_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='auth_sessions' THEN
  IF ROW(NEW.id,NEW.user_account_id,NEW.created_at,NEW.authenticated_at,NEW.absolute_expires_at,NEW.refresh_limit_key)
    IS DISTINCT FROM ROW(OLD.id,OLD.user_account_id,OLD.created_at,OLD.authenticated_at,OLD.absolute_expires_at,OLD.refresh_limit_key)
    OR (OLD.revoked_at IS NOT NULL AND ROW(NEW.revoked_at,NEW.revocation_reason) IS DISTINCT FROM ROW(OLD.revoked_at,OLD.revocation_reason))
    OR NEW.last_used_at<OLD.last_used_at THEN RAISE EXCEPTION 'AUTH_SESSION_IMMUTABLE'; END IF;
 ELSE
  IF ROW(NEW.session_id,NEW.generation,NEW.token_hash,NEW.issued_at) IS DISTINCT FROM ROW(OLD.session_id,OLD.generation,OLD.token_hash,OLD.issued_at)
   OR (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at)
   THEN RAISE EXCEPTION 'AUTH_TOKEN_IMMUTABLE'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER auth_session_history BEFORE UPDATE ON auth_sessions FOR EACH ROW EXECUTE FUNCTION guard_auth_session_history();
CREATE TRIGGER auth_token_history BEFORE UPDATE ON auth_refresh_tokens FOR EACH ROW EXECUTE FUNCTION guard_auth_session_history();
