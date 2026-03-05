# FieldIQ — Phase 1 Implementation Plan
**Target: Working cross-team scheduling negotiation demo + iOS MVP**
**Timeline: Months 1–4 | Stack: Kotlin + Node.js/TS + React Native Expo + Docker**

---

## Repo Structure — Set This Up First

Before writing a single line of application code, establish the monorepo
layout. Everything lives under `vdefran21/fieldiq`.

```
fieldiq/
├── CLAUDE.md                        ← Claude Code context (create Day 1)
├── docker-compose.yml               ← Full local dev environment
├── docker-compose.test.yml          ← Isolated test DB for CI
├── .env.example                     ← Committed env template (no secrets)
├── .env.local                       ← Git-ignored, your actual dev values
├── .github/
│   └── workflows/
│       ├── backend-ci.yml
│       ├── agent-ci.yml
│       └── mobile-ci.yml
├── backend/                         ← Kotlin Spring Boot API
│   ├── build.gradle.kts
│   ├── src/main/kotlin/com/fieldiq/
│   │   ├── FieldIQApplication.kt
│   │   ├── config/
│   │   ├── domain/                  ← Data models / entities
│   │   ├── repository/              ← Spring Data JPA repos
│   │   ├── service/                 ← Business logic
│   │   ├── api/                     ← REST controllers
│   │   ├── negotiation/             ← Cross-team protocol (core IP)
│   │   └── websocket/               ← Real-time event push
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/            ← Flyway migrations (V1__, V2__ etc.)
│   └── src/test/
├── agent/                           ← Node.js/TypeScript AI agent layer
│   ├── package.json
│   ├── tsconfig.json
│   ├── src/
│   │   ├── index.ts
│   │   ├── agents/
│   │   │   ├── scheduling.agent.ts  ← Availability detection + window ranking
│   │   │   ├── communication.agent.ts ← RSVP/reminder drafting via Claude Haiku
│   │   │   └── negotiation.agent.ts ← Orchestrates cross-team protocol calls
│   │   ├── workers/                 ← SQS consumer workers
│   │   ├── integrations/
│   │   │   ├── google-calendar.ts
│   │   │   └── anthropic.ts         ← Claude API client (Haiku by default)
│   │   └── types/
│   └── tests/
├── mobile/                          ← React Native (Expo)
│   ├── app.json
│   ├── package.json
│   ├── app/                         ← Expo Router file-based routing
│   │   ├── (auth)/
│   │   │   └── login.tsx
│   │   ├── (app)/
│   │   │   ├── index.tsx            ← Home / schedule feed
│   │   │   ├── team.tsx             ← Roster view
│   │   │   ├── negotiate.tsx        ← Negotiation approval screen
│   │   │   └── settings.tsx
│   │   └── _layout.tsx
│   ├── components/
│   └── services/
│       └── api.ts                   ← Backend API client
└── shared/
    └── types/                       ← Shared TypeScript types (API contracts)
```

---

## Week 1 — Local Environment + Skeleton

### 1. `docker-compose.yml`

This is your entire local infrastructure. One command, zero cost.

```yaml
version: '3.9'
services:

  postgres:
    image: postgres:16-alpine
    container_name: fieldiq-db
    environment:
      POSTGRES_DB: fieldiq
      POSTGRES_USER: fieldiq
      POSTGRES_PASSWORD: localdev
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U fieldiq"]
      interval: 5s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    container_name: fieldiq-redis
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
    volumes:
      - redis_data:/data

  localstack:
    image: localstack/localstack:3
    container_name: fieldiq-localstack
    ports:
      - "4566:4566"
    environment:
      - SERVICES=sqs,s3,secretsmanager
      - DEFAULT_REGION=us-east-1
      - LOCALSTACK_AUTH_TOKEN=${LOCALSTACK_AUTH_TOKEN:-}
    volumes:
      - localstack_data:/var/lib/localstack
      - ./infra/localstack-init.sh:/etc/localstack/init/ready.d/init.sh

  # Second Postgres for cross-team negotiation protocol testing
  # Simulates Team B's FieldIQ instance on a separate DB
  postgres-team-b:
    image: postgres:16-alpine
    container_name: fieldiq-db-team-b
    environment:
      POSTGRES_DB: fieldiq_team_b
      POSTGRES_USER: fieldiq
      POSTGRES_PASSWORD: localdev
    ports:
      - "5433:5432"
    volumes:
      - postgres_data_b:/var/lib/postgresql/data

volumes:
  postgres_data:
  postgres_data_b:
  redis_data:
  localstack_data:
```

`infra/localstack-init.sh` — auto-creates SQS queues on startup:
```bash
#!/bin/bash
awslocal sqs create-queue --queue-name fieldiq-agent-tasks
awslocal sqs create-queue --queue-name fieldiq-notifications
awslocal sqs create-queue --queue-name fieldiq-negotiation
```

### 2. `.env.example`

```bash
# Backend
DATABASE_URL=jdbc:postgresql://localhost:5432/fieldiq
DATABASE_USERNAME=fieldiq
DATABASE_PASSWORD=localdev
REDIS_URL=redis://localhost:6379
JWT_SECRET=dev-secret-change-in-production

# Agent layer
ANTHROPIC_API_KEY=your-key-here
AWS_ENDPOINT_URL=http://localhost:4566   # LocalStack
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
AWS_REGION=us-east-1
AGENT_TASKS_QUEUE_URL=http://localhost:4566/000000000000/fieldiq-agent-tasks
NOTIFICATIONS_QUEUE_URL=http://localhost:4566/000000000000/fieldiq-notifications
NEGOTIATION_QUEUE_URL=http://localhost:4566/000000000000/fieldiq-negotiation

# Cross-team protocol
FIELDIQ_INSTANCE_ID=team-a-local         # Unique per instance
FIELDIQ_INSTANCE_BASE_URL=http://localhost:8080

# For team-b test instance
TEAM_B_BASE_URL=http://localhost:8081

# Google Calendar OAuth
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
GOOGLE_REDIRECT_URI=http://localhost:8080/auth/google/callback

# Auth (magic link / OTP)
TWILIO_ACCOUNT_SID=     # For SMS OTP — leave blank to use email in dev
TWILIO_AUTH_TOKEN=
SENDGRID_API_KEY=       # For email magic link in dev
```

---

## Week 2–3 — Data Model

This is the most important design work of Phase 1. Get it right before
writing backend code. All changes via Flyway migrations — never alter
the DB directly.

### Core Schema (`V1__initial_schema.sql`)

```sql
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

-- Users are parents or coaches — NOT players directly
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
    opponent_team_id UUID,                   -- for games: links to opposing team
    negotiation_id  UUID,                    -- FK added after negotiation_sessions table
    created_by      UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Availability windows declared by team members
-- These feed the Scheduling Agent
CREATE TABLE availability_windows (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id         UUID NOT NULL REFERENCES teams(id),
    user_id         UUID NOT NULL REFERENCES users(id),
    day_of_week     SMALLINT,                -- 0=Sun, 6=Sat (for recurring)
    specific_date   DATE,                    -- for one-off blackouts/availability
    start_time      TIME,
    end_time        TIME,
    window_type     VARCHAR(15) NOT NULL CHECK (window_type IN ('available','unavailable')),
    source          VARCHAR(15) NOT NULL DEFAULT 'manual'
                    CHECK (source IN ('manual','google_cal','apple_cal')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Google Calendar OAuth tokens per user
CREATE TABLE calendar_integrations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) UNIQUE,
    provider        VARCHAR(20) NOT NULL DEFAULT 'google',
    access_token    TEXT NOT NULL,           -- encrypted at rest
    refresh_token   TEXT NOT NULL,           -- encrypted at rest
    expires_at      TIMESTAMPTZ NOT NULL,
    scope           TEXT,
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Cross-Team Negotiation Schema (`V2__negotiation_schema.sql`)

This is your core IP. Design it carefully.

```sql
-- A negotiation session between two FieldIQ team instances
-- One session = one attempt to schedule a single game
CREATE TABLE negotiation_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Initiating side
    initiator_team_id   UUID NOT NULL REFERENCES teams(id),
    initiator_instance  VARCHAR(255) NOT NULL, -- base URL of initiating FieldIQ instance
    initiator_manager   UUID REFERENCES users(id),

    -- Responding side (may be on a different FieldIQ instance)
    responder_team_id   UUID,                  -- null until responder joins
    responder_instance  VARCHAR(255),          -- base URL of responding instance
    responder_external_id VARCHAR(255),        -- their team ID in their system

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
    preferred_day_of_week       SMALLINT[],   -- e.g. {6, 0} = Sat/Sun preferred

    -- The agreed-upon slot (populated when status = confirmed)
    agreed_starts_at    TIMESTAMPTZ,
    agreed_location     VARCHAR(255),

    -- Metadata
    invite_token        VARCHAR(255) UNIQUE,   -- shared secret for cross-instance auth
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Individual slot proposals exchanged during negotiation
-- Each round: initiator proposes N slots, responder accepts/rejects/counters
CREATE TABLE negotiation_proposals (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID NOT NULL REFERENCES negotiation_sessions(id),
    proposed_by         VARCHAR(10) NOT NULL CHECK (proposed_by IN ('initiator','responder')),
    round_number        INTEGER NOT NULL DEFAULT 1,

    -- JSON array of proposed time slots
    -- [{"starts_at": "2026-04-05T10:00:00Z", "ends_at": "...", "location": "..."}]
    slots               JSONB NOT NULL,

    response_status     VARCHAR(15) CHECK (response_status IN ('pending','accepted','rejected','countered')),
    rejection_reason    VARCHAR(255),       -- 'no_availability', 'location_conflict', etc.

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Audit log for the negotiation — every state change recorded
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

-- Indexes for performance
CREATE INDEX idx_negotiation_sessions_initiator ON negotiation_sessions(initiator_team_id);
CREATE INDEX idx_negotiation_sessions_status ON negotiation_sessions(status);
CREATE INDEX idx_negotiation_proposals_session ON negotiation_proposals(session_id);
CREATE INDEX idx_availability_windows_team ON availability_windows(team_id);
CREATE INDEX idx_availability_windows_user ON availability_windows(user_id);
CREATE INDEX idx_events_team ON events(team_id, starts_at);
```

---

## Weeks 3–6 — Kotlin Spring Boot Backend

### `build.gradle.kts` dependencies

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Database
    implementation("org.flywaydb:flyway-core")
    implementation("org.postgresql:postgresql")

    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // AWS SDK (LocalStack-compatible)
    implementation("software.amazon.awssdk:sqs:2.25.0")
    implementation("software.amazon.awssdk:secretsmanager:2.25.0")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.3")

    // HTTP client (for cross-instance negotiation calls)
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Serialization
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:postgresql:1.19.0")
}
```

### REST API Surface — Phase 1 Endpoints

```
AUTH
  POST /auth/request-otp          { phone or email } → sends OTP
  POST /auth/verify-otp           { channel, value, otp } → JWT
  POST /auth/refresh               { refreshToken } → new JWT

TEAMS
  GET  /teams/:teamId              → team + members
  POST /teams                      → create team
  POST /teams/:teamId/members      → add member (manager/coach/parent)

AVAILABILITY
  GET  /teams/:teamId/availability → aggregate team availability
  POST /users/me/availability      → declare availability window
  POST /users/me/calendar/connect  → initiate Google Calendar OAuth
  GET  /auth/google/callback       → OAuth callback, stores tokens

EVENTS
  GET  /teams/:teamId/events       → upcoming events
  POST /teams/:teamId/events       → create event (game/practice)
  PATCH /events/:eventId           → update event

SCHEDULING AGENT (deterministic — no LLM)
  POST /teams/:teamId/suggest-windows
    Body: { opponentTeamId, dateRangeStart, dateRangeEnd, durationMinutes }
    Returns: ranked list of mutually available time windows

NEGOTIATION (core IP — detailed below)
  POST /negotiations               → initiate cross-team negotiation session
  GET  /negotiations/:sessionId    → get session state
  POST /negotiations/:sessionId/join          → responder joins session
  POST /negotiations/:sessionId/propose       → send slot proposals
  POST /negotiations/:sessionId/respond       → accept/reject/counter proposals
  POST /negotiations/:sessionId/confirm       → human confirms agreed slot
  POST /negotiations/:sessionId/cancel        → withdraw

  CROSS-INSTANCE (called by remote FieldIQ instances — not by mobile app)
  POST /api/negotiate/incoming     → receive negotiation invite from remote instance
  POST /api/negotiate/:sessionId/relay        → relay proposal/response from remote
```

### The Cross-Team Negotiation Protocol

This is the IP. Here's the complete flow design:

```
TEAM A (initiator — your wife's team)        TEAM B (responder — opposing manager)
on FieldIQ Instance A                        on FieldIQ Instance B (or same instance in beta)

1. Manager A hits POST /negotiations
   - Creates negotiation_session (status: pending_response)
   - Generates invite_token
   - Sends invite to Team B manager via SMS/email
     containing a deep-link with the invite_token

2. Manager B opens deep-link → joins session
   POST /negotiations/:id/join (on Instance B)
   - If Team B is also on FieldIQ: full protocol runs
   - If Team B is NOT on FieldIQ: fallback to manual
     (they see available windows, pick one, confirm)

3. Instance A runs SchedulingAgent:
   - Queries availability_windows for Team A members
   - Pulls Google Calendar events for connected members
   - Computes available windows for Team A
   - Sends top 5 candidate slots to Instance B
   POST /api/negotiate/:sessionId/relay  (on Instance B)

4. Instance B runs SchedulingAgent:
   - Computes Team B availability
   - Intersects with proposed slots from Instance A
   - If intersection found → sends matched slots back
   - If no intersection → counters with Team B's top 5 windows

5. Repeat steps 3-4 up to MAX_ROUNDS (3 by default)
   Each round, window sets narrow toward mutual availability

6. When match found:
   - Both instances set status = 'pending_approval'
   - Push notification to both managers
   - Managers see: "We found a time: Saturday April 5 at 10am. Confirm?"

7. Both managers confirm → status = 'confirmed'
   - Events created on both teams' calendars
   - Confirmation messages sent to all team members

FALLBACK (Team B not on FieldIQ):
   - Instance A sends a simple scheduling link to Team B manager
   - They see Team A's available windows (no AI — just a picker)
   - They select a time → confirmation sent to both managers
   - This is still better than texting back and forth
```

### `NegotiationService.kt` — Key Methods to Implement

```kotlin
// Initiates a new negotiation session
suspend fun initiateNegotiation(
    initiatorTeamId: UUID,
    request: InitiateNegotiationRequest
): NegotiationSession

// Called when responder joins (may be on remote instance)
suspend fun joinSession(
    sessionId: UUID,
    inviteToken: String,
    responderTeamId: UUID,
    responderInstance: String
): NegotiationSession

// Computes available windows for a team and sends to counterpart
suspend fun generateAndSendProposal(
    sessionId: UUID,
    actor: NegotiationActor    // INITIATOR or RESPONDER
): NegotiationProposal

// Processes incoming proposal from counterpart instance
// Returns: matched slots (if any) or counter-proposal
suspend fun processIncomingProposal(
    sessionId: UUID,
    incomingProposal: NegotiationProposalDto
): ProposalProcessingResult

// Seals confirmed agreement, creates events on both teams
suspend fun confirmAgreement(
    sessionId: UUID,
    confirmedSlot: TimeSlot,
    confirmedBy: UUID
): Event
```

---

## Weeks 4–7 — Node.js Agent Layer

The agent layer handles all async work — calendar sync, LLM calls,
SQS message processing. It runs as a separate process.

### `scheduling.agent.ts` — Pure TypeScript, No LLM

```typescript
// This agent is entirely deterministic — no AI needed for basic availability
// LLM is only used in negotiation.agent.ts for conflict resolution edge cases

interface TimeWindow {
  startsAt: Date;
  endsAt: Date;
  confidence: number;  // 0-1, based on how many members are available
}

export class SchedulingAgent {
  // Main entry point: given a team + constraints, return ranked windows
  async findAvailableWindows(params: {
    teamId: string;
    dateRangeStart: Date;
    dateRangeEnd: Date;
    durationMinutes: number;
    preferredDays?: number[];   // 0=Sun, 6=Sat
  }): Promise<TimeWindow[]> {
    // 1. Fetch availability_windows from DB for all team members
    // 2. Fetch calendar events from Google Calendar for connected members
    // 3. Build a timeline of busy/free blocks
    // 4. Find contiguous free blocks >= durationMinutes
    // 5. Score each window by member availability %
    // 6. Filter by preferred days
    // 7. Return top 10 windows sorted by score DESC
  }

  // Finds the intersection of two teams' available windows
  // Called during cross-team negotiation
  intersectWindows(
    teamAWindows: TimeWindow[],
    teamBWindows: TimeWindow[]
  ): TimeWindow[] {
    // Overlap detection algorithm
    // Returns windows where BOTH teams have availability >= threshold (e.g. 70%)
  }
}
```

### `communication.agent.ts` — Claude Haiku for Message Drafting

```typescript
// Uses Claude Haiku — fast and cheap (~$0.001 per RSVP message batch)
// Never used for scheduling logic — only for natural language drafting

export class CommunicationAgent {
  private anthropic: Anthropic;

  async draftEventReminder(params: {
    eventType: 'game' | 'practice';
    teamName: string;
    opponentName?: string;
    startsAt: Date;
    location: string;
    playerName: string;   // personalized per parent
  }): Promise<string> {
    // Prompt: "Draft a friendly 2-sentence reminder for a youth soccer
    //          {game/practice}. Tone: warm, brief, parent-friendly.
    //          Include time and location. Address the parent of {playerName}."
    // Returns plain text — backend decides SMS vs push vs email
  }

  async draftRsvpFollowUp(params: {
    playerName: string;
    eventDescription: string;
    daysSinceOriginalMessage: number;
  }): Promise<string> {
    // Gentle follow-up for non-responders
    // Escalates urgency slightly if daysSince > 3
  }

  async draftNegotiationOutcome(params: {
    outcome: 'confirmed' | 'no_slots_found';
    agreedTime?: Date;
    agreedLocation?: string;
    teamName: string;
  }): Promise<string> {
    // Notifies manager of negotiation result
  }
}
```

### SQS Worker Pattern

```typescript
// workers/agent-task-worker.ts
// Runs continuously, polls SQS for tasks

interface AgentTask {
  taskType: 'SYNC_CALENDAR' | 'SEND_REMINDERS' | 'RUN_NEGOTIATION_ROUND' | 'SEND_NOTIFICATION';
  payload: Record<string, unknown>;
  teamId: string;
  priority: 'high' | 'normal';
}

// Task types and when they're triggered:
// SYNC_CALENDAR       → when user connects Google Calendar or every 4 hours
// SEND_REMINDERS      → 24h and 2h before each event
// RUN_NEGOTIATION_ROUND → when a negotiation proposal arrives
// SEND_NOTIFICATION   → push/SMS/email dispatch
```

---

## Weeks 6–10 — React Native (Expo) App

iOS first. Keep it minimal — the AI does the work, the UI just shows
results and asks for approval.

### Screen Inventory — Phase 1 Only

```
/login                  Phone number entry → OTP verify → JWT stored
/(app)/index            Schedule feed: upcoming events, pending negotiations
/(app)/team             Roster list, member availability status
/(app)/negotiate/:id    Negotiation approval screen (the key UX moment)
/(app)/settings         Calendar connect, notification preferences
```

### Negotiation Approval Screen — Most Important UX

```tsx
// This screen is THE killer feature made visible to the user
// When the AI finds a mutual time, this is what the manager sees

export default function NegotiationApprovalScreen() {
  // Shows:
  // ─────────────────────────────────
  //  🤝 Match Found!
  //  FieldIQ found a time that works
  //  for both teams.
  //
  //  vs. [Opponent Team Name]
  //  📅 Saturday, April 5
  //  ⏰ 10:00 AM – 11:30 AM
  //  📍 [Location]
  //
  //  [Confirm Game]    [Suggest Different Time]
  // ─────────────────────────────────
  // The "Suggest Different Time" button restarts the negotiation
  // with the manager's new constraints. This is intentional —
  // the manager stays in control, the AI does the legwork.
}
```

### `services/api.ts` — Backend Client

```typescript
// Single API client — all screens import from here
// In dev: points to localhost:8080
// In beta: points to Railway/Render URL

const API_BASE = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080';

export const api = {
  auth: {
    requestOtp: (contact: string, channel: 'sms' | 'email') => ...,
    verifyOtp: (contact: string, otp: string) => ...,
  },
  team: {
    get: (teamId: string) => ...,
    getEvents: (teamId: string) => ...,
    suggestWindows: (teamId: string, params: WindowParams) => ...,
  },
  negotiation: {
    initiate: (params: InitiateParams) => ...,
    getSession: (sessionId: string) => ...,
    confirm: (sessionId: string, slot: TimeSlot) => ...,
    cancel: (sessionId: string) => ...,
  },
};
```

---

## Auth Layer — Phone OTP (Passwordless)

Parents will not remember passwords. Phone OTP is the right call.

```
Flow:
1. User enters phone number in app
2. Backend generates 6-digit OTP, stores hashed version with 10-min expiry
3. OTP sent via Twilio SMS (or email via SendGrid in dev — cheaper)
4. User enters OTP → backend verifies hash → issues JWT (15min) + refresh token (30 days)
5. Refresh token stored in SecureStore (React Native) — never in AsyncStorage

Dev shortcut: if phone starts with +1555, accept OTP "000000" without
sending SMS. Saves Twilio costs during development.
```

---

## Google Calendar Integration

```
OAuth Flow:
1. User taps "Connect Google Calendar" in Settings
2. App opens: GET /auth/google/authorize → redirects to Google OAuth
3. User grants permission
4. Google redirects to: /auth/google/callback?code=...
5. Backend exchanges code for access_token + refresh_token
6. Tokens stored encrypted in calendar_integrations table
7. Agent worker runs SYNC_CALENDAR task immediately, then every 4 hours

What we pull from Google Calendar:
- Busy blocks only (we never read event titles/details — privacy matters)
- Stored as availability_windows with source='google_cal'
- Refreshed automatically using stored refresh_token

Scopes needed:
- https://www.googleapis.com/auth/calendar.events.readonly
  (read-only, busy/free only — minimal permissions, trust-building)
```

---

## GitHub Actions CI

### `.github/workflows/backend-ci.yml`

```yaml
name: Backend CI
on:
  push:
    paths: ['backend/**']
  pull_request:
    paths: ['backend/**']

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16
        env:
          POSTGRES_DB: fieldiq_test
          POSTGRES_USER: fieldiq
          POSTGRES_PASSWORD: testpass
        ports: ['5432:5432']
        options: --health-cmd pg_isready --health-interval 5s
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - name: Run tests
        working-directory: backend
        run: ./gradlew test
        env:
          DATABASE_URL: jdbc:postgresql://localhost:5432/fieldiq_test
          DATABASE_USERNAME: fieldiq
          DATABASE_PASSWORD: testpass
          JWT_SECRET: test-secret
```

---

## Sprint Breakdown — 16 Weeks

```
Sprint 1  (Weeks 1–2)   Repo setup, Docker Compose, data model v1 + v2,
                         Flyway migrations, CLAUDE.md, backend skeleton
                         running against local Postgres

Sprint 2  (Weeks 3–4)   Auth layer (OTP + JWT), team + member CRUD,
                         availability window CRUD, basic event CRUD,
                         unit tests for all new services

Sprint 3  (Weeks 5–6)   Google Calendar OAuth + token storage,
                         calendar sync agent worker (SQS + LocalStack),
                         SchedulingAgent v1 (deterministic window finder),
                         POST /suggest-windows endpoint working

Sprint 4  (Weeks 7–8)   Cross-team negotiation protocol v1:
                         NegotiationService + all DB operations,
                         /negotiations endpoints (initiate, join, propose, respond),
                         full round-trip test with two local Postgres instances

Sprint 5  (Weeks 9–10)  React Native Expo app:
                         Login screen, OTP flow, Schedule feed,
                         Team/roster screen, Settings + calendar connect

Sprint 6  (Weeks 11–12) Negotiation approval UX in mobile app,
                         push notifications (Expo + FCM),
                         CommunicationAgent (Haiku) for reminders,
                         RSVP tracking on events

Sprint 7  (Weeks 13–14) End-to-end test: two managers on two devices,
                         full negotiation from initiate to confirmed event,
                         fix everything that breaks, polish the happy path

Sprint 8  (Weeks 15–16) Invite 5 real DMV soccer managers (your wife + 4),
                         instrument time-saved metric,
                         fix friction points from real usage,
                         prepare for 15-team beta launch (Phase 2)
```

---

## Testing Strategy

```
Backend unit tests     → JUnit 5 + Mockk — all Service layer methods
Backend integration    → TestContainers (real Postgres in CI, not H2)
Negotiation protocol   → Integration test: two Spring Boot contexts,
                          full round-trip in a single test class
Agent layer            → Jest — mock Anthropic API, mock SQS
Mobile                 → Detox for E2E on iOS simulator (Sprint 7+)

North star test (Sprint 7):
  Given: Team A and Team B both on FieldIQ with calendar sync
  When:  Team A manager initiates negotiation
  Then:  Within 60 seconds, both managers receive a push notification
         with a proposed game time that fits both teams' availability
         AND confirming schedules the event on both teams' calendars
```

---

## What NOT to Build in Phase 1

Resist scope creep. These are explicitly out of scope:

- ❌ Stripe / payment processing — wire it in Phase 2
- ❌ Club admin dashboard — Phase 2
- ❌ Tournament scheduling — Phase 3
- ❌ Android app — Phase 2 (iOS only)
- ❌ Field booking API integrations — manual field entry is fine for beta
- ❌ Multilingual support — post-beta
- ❌ Volunteer/snack rotation — post-beta
- ❌ Player performance tracking — not FieldIQ's product

Every feature not on the list above is a distraction from the one thing
that matters in Phase 1: **prove the cross-team negotiation protocol works
with real people on real devices.**
