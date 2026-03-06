-- Drop the UNIQUE constraint on auth_tokens.token_hash.
--
-- The unique constraint causes duplicate-key failures when the same OTP hash
-- is generated more than once (e.g., dev bypass numbers always produce "000000").
-- In production, two users could also randomly receive the same 6-digit code.
--
-- The constraint is unnecessary because verification lookups filter by
-- (channel, token_hash, used_at IS NULL), so duplicate hashes on used tokens
-- are harmless. An index on (token_hash) is kept for lookup performance.
-- See docs/01_Phase1_Schema.md

ALTER TABLE auth_tokens DROP CONSTRAINT auth_tokens_token_hash_key;

CREATE INDEX idx_auth_tokens_hash_lookup ON auth_tokens (channel, token_hash)
    WHERE used_at IS NULL;
