# FieldIQ -- Phase 1 Schema

All changes via Flyway migrations -- never alter the DB directly.
This is the most important design work of Phase 1. Get it right before writing backend code.

---

## Core Schema (`V1__initial_schema.sql`)

```sql
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
-- IMPLEMENTATION NOTE: access_token and refresh_token MUST be encrypted at rest
-- using AES-256-GCM via a KMS-managed key (AWS Secrets Manager in prod, env var in dev).
-- The Kotlin entity uses a JPA AttributeConverter (TokenEncryptionConverter) that
-- encrypts on write and decrypts on read. Never store plaintext OAuth tokens.
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
```

---

## Cross-Team Negotiation Schema (`V2__negotiation_schema.sql`)

This is your core IP. Design it carefully.

```sql
-- A negotiation session between two FieldIQ team instances
-- One session = one attempt to schedule a single game
CREATE TABLE negotiation_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Initiating side
    initiator_team_id   UUID NOT NULL REFERENCES teams(id),
    initiator_instance  VARCHAR(255) NOT NULL,  -- base URL of initiating FieldIQ instance
    initiator_manager   UUID REFERENCES users(id),

    -- Responding side (may be on a different FieldIQ instance)
    responder_team_id   UUID,                   -- null until responder joins
    responder_instance  VARCHAR(255),           -- base URL of responding instance
    responder_external_id VARCHAR(255),         -- their team ID in their system

    -- Negotiation state machine
    -- pending_response: waiting for responder to join
    -- proposing: slots being exchanged
    -- pending_approval: both sides have a match, awaiting human confirm
    -- confirmed: game is scheduled
    -- failed: no mutual slot found or expired
    -- cancelled: one side withdrew
    status              VARCHAR(25) NOT NULL DEFAULT 'pending_response'
                        CHECK (status IN (
                            'pending_response','proposing','pending_approval',
                            'confirmed','failed','cancelled'
                        )),

    -- Constraints passed in by the initiator
    requested_date_range_start  DATE,
    requested_date_range_end    DATE,
    requested_duration_minutes  INTEGER NOT NULL DEFAULT 90,
    -- Backend converts to Set<DayOfWeek> in SchedulingService for clean Kotlin interop
    preferred_day_of_week       SMALLINT[],     -- e.g. {6, 0} = Sat/Sun preferred

    -- The agreed-upon slot (populated when status = confirmed)
    agreed_starts_at    TIMESTAMPTZ,
    agreed_location     VARCHAR(255),

    -- Auth
    invite_token        VARCHAR(255) UNIQUE,    -- single-use bearer for join handshake
    session_key_hash    VARCHAR(255),           -- hash of derived HMAC key (for audit)

    -- Metadata
    max_rounds          INTEGER NOT NULL DEFAULT 3,
    current_round       INTEGER NOT NULL DEFAULT 0,
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Individual slot proposals exchanged during negotiation
-- Each round: one side proposes N slots, other side accepts/rejects/counters
CREATE TABLE negotiation_proposals (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID NOT NULL REFERENCES negotiation_sessions(id),
    proposed_by         VARCHAR(10) NOT NULL CHECK (proposed_by IN ('initiator','responder')),
    round_number        INTEGER NOT NULL DEFAULT 1,

    -- JSON array of proposed time slots
    -- Schema: [{"starts_at": "ISO8601", "ends_at": "ISO8601", "location": "string|null"}]
    slots               JSONB NOT NULL,
    schema_version      INTEGER NOT NULL DEFAULT 1,   -- for future-proofing JSONB shape

    response_status     VARCHAR(15) DEFAULT 'pending'
                        CHECK (response_status IN ('pending','accepted','rejected','countered')),
    rejection_reason    VARCHAR(255),       -- 'no_availability', 'location_conflict', etc.

    -- Idempotency: unique per (session, round, actor) prevents duplicate proposals
    UNIQUE (session_id, round_number, proposed_by),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Audit log for the negotiation -- every state change recorded
CREATE TABLE negotiation_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES negotiation_sessions(id),
    event_type      VARCHAR(50) NOT NULL,   -- 'session_created', 'proposal_sent', etc.
    actor           VARCHAR(10) CHECK (actor IN ('initiator','responder','system')),
    payload         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Add FK from events back to negotiation_sessions
ALTER TABLE events
    ADD CONSTRAINT fk_events_negotiation
    FOREIGN KEY (negotiation_id) REFERENCES negotiation_sessions(id);

-- Indexes
CREATE INDEX idx_negotiation_sessions_initiator ON negotiation_sessions(initiator_team_id);
CREATE INDEX idx_negotiation_sessions_status ON negotiation_sessions(status);
CREATE INDEX idx_negotiation_proposals_session ON negotiation_proposals(session_id);
CREATE INDEX idx_negotiation_events_session ON negotiation_events(session_id);
```

---

## OTP Rate Limiting (`V3__rate_limiting.sql`)

```sql
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
```

**Rate limit rules (enforced via Redis, persisted to DB for audit):**
- Max 3 OTP requests per phone/email per 15-minute window
- Max 10 OTP requests per phone/email per 24 hours
- After 5 failed verification attempts, block for 1 hour
- Dev bypass: `+1555*` numbers are exempt
