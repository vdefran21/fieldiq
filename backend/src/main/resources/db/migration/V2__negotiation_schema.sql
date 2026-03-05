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
