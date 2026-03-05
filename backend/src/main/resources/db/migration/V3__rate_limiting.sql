-- Rate limiting for OTP requests (prevents SMS pumping)
-- Checked by Redis in real-time; this table is for audit/persistence
CREATE TABLE otp_rate_limits (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identifier  VARCHAR(255) NOT NULL,     -- phone or email
    attempts    INTEGER NOT NULL DEFAULT 1,
    window_start TIMESTAMPTZ NOT NULL DEFAULT now(),
    blocked_until TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_otp_rate_limits_identifier ON otp_rate_limits(identifier, window_start);
