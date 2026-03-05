-- Required for gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Orgs represent clubs or rec leagues (the paying entity)
CREATE TABLE organizations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    slug        VARCHAR(100) UNIQUE NOT NULL,  -- used in negotiation URLs
    timezone    VARCHAR(50) NOT NULL DEFAULT 'America/New_York',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Teams belong to an org
CREATE TABLE teams (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID REFERENCES organizations(id),
    name            VARCHAR(255) NOT NULL,
    sport           VARCHAR(50) NOT NULL DEFAULT 'soccer',
    age_group       VARCHAR(20),             -- e.g. "U10", "U14"
    season          VARCHAR(20),             -- e.g. "Spring2026"
    external_id     VARCHAR(255),            -- for future migration tools
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Users are parents or coaches -- NOT players directly
-- COPPA NOTE: No child PII is stored in this table. Only parent/coach contact info.
-- Child names appear only in team_members.player_name for roster display purposes.
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone           VARCHAR(20) UNIQUE,
    email           VARCHAR(255) UNIQUE,
    display_name    VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Many-to-many: user's role on a team
CREATE TABLE team_members (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id     UUID NOT NULL REFERENCES teams(id),
    user_id     UUID NOT NULL REFERENCES users(id),
    role        VARCHAR(20) NOT NULL CHECK (role IN ('manager','coach','parent')),
    player_name VARCHAR(255),                -- the child's name (if parent)
    is_active   BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (team_id, user_id)
);

-- OTP / magic link tokens for passwordless auth
CREATE TABLE auth_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES users(id),
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    channel     VARCHAR(10) NOT NULL CHECK (channel IN ('sms','email')),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Refresh tokens for session management (hashed, rotatable)
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id),
    token_hash      VARCHAR(255) NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    rotated_from    UUID REFERENCES refresh_tokens(id),  -- previous token in chain
    device_info     VARCHAR(255),           -- e.g. "iPhone 15, iOS 19"
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Events: games, practices, or other scheduled items
CREATE TABLE events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id         UUID NOT NULL REFERENCES teams(id),
    event_type      VARCHAR(20) NOT NULL CHECK (event_type IN ('game','practice','tournament','other')),
    title           VARCHAR(255),
    location        VARCHAR(255),
    location_notes  TEXT,
    starts_at       TIMESTAMPTZ,             -- null = unscheduled
    ends_at         TIMESTAMPTZ,
    status          VARCHAR(20) NOT NULL DEFAULT 'scheduled'
                    CHECK (status IN ('draft','scheduled','cancelled','completed')),
    -- For games: opponent reference
    -- Same-instance opponent: use opponent_team_id
    -- Cross-instance opponent: use opponent_name + opponent_external_ref
    opponent_team_id       UUID,
    opponent_name          VARCHAR(255),           -- "Bethesda Fire U12" (for display)
    opponent_external_ref  VARCHAR(255),           -- their team ID on their instance
    negotiation_id  UUID,                    -- FK added after negotiation_sessions table
    created_by      UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Availability windows declared by team members
-- These feed the SchedulingService
CREATE TABLE availability_windows (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id         UUID NOT NULL REFERENCES teams(id),
    user_id         UUID NOT NULL REFERENCES users(id),
    day_of_week     SMALLINT CHECK (day_of_week BETWEEN 0 AND 6),
    specific_date   DATE,
    start_time      TIME NOT NULL,
    end_time        TIME NOT NULL,
    window_type     VARCHAR(15) NOT NULL CHECK (window_type IN ('available','unavailable')),
    source          VARCHAR(15) NOT NULL DEFAULT 'manual'
                    CHECK (source IN ('manual','google_cal','apple_cal')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Exactly one of day_of_week or specific_date must be set
    CONSTRAINT chk_window_date_type CHECK (
        (day_of_week IS NOT NULL AND specific_date IS NULL)
        OR (day_of_week IS NULL AND specific_date IS NOT NULL)
    ),
    -- start must be before end
    CONSTRAINT chk_window_time_order CHECK (start_time < end_time)
);

-- Google Calendar OAuth tokens per user
-- access_token and refresh_token are encrypted at rest via TokenEncryptionConverter (AES-256-GCM)
CREATE TABLE calendar_integrations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) UNIQUE,
    provider        VARCHAR(20) NOT NULL DEFAULT 'google',
    access_token    TEXT NOT NULL,           -- encrypted via TokenEncryptionConverter
    refresh_token   TEXT NOT NULL,           -- encrypted via TokenEncryptionConverter
    expires_at      TIMESTAMPTZ NOT NULL,
    scope           TEXT,
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Push notification device tokens
CREATE TABLE user_devices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id),
    expo_push_token VARCHAR(255) NOT NULL,
    platform        VARCHAR(10) NOT NULL CHECK (platform IN ('ios','android')),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, expo_push_token)
);

-- RSVP responses to events
CREATE TABLE event_responses (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id    UUID NOT NULL REFERENCES events(id),
    user_id     UUID NOT NULL REFERENCES users(id),
    status      VARCHAR(15) NOT NULL CHECK (status IN ('going','not_going','maybe','no_response')),
    responded_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, user_id)
);

-- Indexes
CREATE INDEX idx_team_members_team ON team_members(team_id);
CREATE INDEX idx_team_members_user ON team_members(user_id);
CREATE INDEX idx_events_team ON events(team_id, starts_at);
CREATE INDEX idx_availability_windows_team ON availability_windows(team_id);
CREATE INDEX idx_availability_windows_user ON availability_windows(user_id);
CREATE INDEX idx_event_responses_event ON event_responses(event_id);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_user_devices_user ON user_devices(user_id);
