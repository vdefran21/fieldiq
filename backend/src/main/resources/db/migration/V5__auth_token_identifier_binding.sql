-- Bind OTP tokens to the identifier (phone/email) they were requested for.
--
-- Previously, auth_tokens stored only (channel, token_hash, expires_at). Verification
-- looked up by (channel, token_hash) — the identifier from the verify request was used
-- solely for user lookup, never validated against the token. This allowed any valid OTP
-- hash to be consumed with any identifier on the same channel (account-confusion risk).
--
-- This migration adds an identifier_hash column containing the SHA-256 hash of the
-- normalized identifier (lowercased email, E.164 phone). The verification query now
-- includes identifier_hash, binding each OTP to the exact phone/email it was issued for.
--
-- The identifier is stored as a hash (not plaintext) to avoid PII exposure if the
-- auth_tokens table is breached. The hash is deterministic, so the same normalized
-- identifier always produces the same hash for lookup.
--
-- See docs/02_Phase1_Auth_Calendar.md for the auth flow.

-- Step 1: Add column with temporary default for existing rows.
-- Existing rows get '' (stale dev tokens); the default is dropped immediately after
-- so new inserts must always provide a real hash.
ALTER TABLE auth_tokens ADD COLUMN identifier_hash VARCHAR(64) NOT NULL DEFAULT '';

-- Step 2: Drop the V4 partial index (channel, token_hash) — replaced by a 3-column index.
DROP INDEX IF EXISTS idx_auth_tokens_hash_lookup;

-- Step 3: Create new partial index matching the updated verification query:
-- findFirstByChannelAndTokenHashAndIdentifierHashAndUsedAtIsNullOrderByCreatedAtDesc
CREATE INDEX idx_auth_tokens_bound_lookup
    ON auth_tokens (channel, token_hash, identifier_hash)
    WHERE used_at IS NULL;

-- Step 4: Remove the temporary default. No new row should ever have an empty identifier_hash.
ALTER TABLE auth_tokens ALTER COLUMN identifier_hash DROP DEFAULT;
