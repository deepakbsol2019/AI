-- Apply after 20261125_user_auth.sql when deploying this recovery update.
-- Retire outstanding legacy reset links. Users can request a fresh PASSWORD_RESET link.
-- Preserves history and all schema relationships. Safe to rerun.
SET time_zone = '+00:00';
UPDATE credential_tokens SET revoked_at=UTC_TIMESTAMP(6)
WHERE purpose='RESET_PASSWORD' AND consumed_at IS NULL AND revoked_at IS NULL;
