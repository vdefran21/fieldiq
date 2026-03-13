# FieldIQ — Architecture Diagrams

Mermaid diagrams for the core system flows. Renders natively on GitHub.

---

## 1. Negotiation Protocol — State Machine

All valid state transitions for a `NegotiationSession`. Terminal states are double-bordered.

```mermaid
stateDiagram-v2
    [*] --> pending_response : Manager A creates session

    pending_response --> proposing : Responder joins via invite_token
    pending_response --> cancelled : Initiator withdraws
    pending_response --> failed : Invite token expires (48h TTL)

    proposing --> pending_approval : Match found (overlapping slots)
    proposing --> failed : Max rounds exceeded OR timeout
    proposing --> cancelled : Either side withdraws

    pending_approval --> confirmed : Both managers confirm
    pending_approval --> proposing : One side requests different time
    pending_approval --> cancelled : Either side withdraws

    confirmed --> [*]
    failed --> [*]
    cancelled --> [*]
```

---

## 2. Negotiation Protocol — Full Sequence Diagram (Happy Path)

End-to-end flow from initiation to confirmed game, across two FieldIQ instances.

```mermaid
sequenceDiagram
    actor ManagerA as Manager A (Mobile)
    participant InstA as Instance A (Backend)
    participant DBA as Postgres A
    participant SQS as SQS
    participant Agent as Agent Layer
    participant InstB as Instance B (Backend)
    participant DBB as Postgres B
    actor ManagerB as Manager B (Mobile)

    Note over ManagerA,ManagerB: Phase 1: Initiation

    ManagerA->>InstA: POST /negotiations<br/>{teamId, dateRange, duration}
    activate InstA
    InstA->>DBA: INSERT negotiation_sessions<br/>(status: pending_response)
    InstA->>InstA: Generate invite_token<br/>(crypto random, 48h TTL)
    InstA->>SQS: SEND_NOTIFICATION<br/>(invite link to Manager B)
    InstA-->>ManagerA: 201 {sessionId, inviteToken}
    deactivate InstA

    SQS->>Agent: Poll SEND_NOTIFICATION
    Agent->>Agent: Draft invite message<br/>(Claude Haiku)
    Agent->>ManagerB: SMS/Push with deep link

    Note over ManagerA,ManagerB: Phase 2: Join Handshake

    ManagerB->>InstB: POST /negotiations/{id}/join<br/>{inviteToken}
    activate InstB
    InstB->>InstA: Validate invite_token<br/>(relay call)
    InstA->>DBA: Consume token (set null)<br/>Derive session HMAC key
    InstA-->>InstB: 200 {sessionKey derived}
    InstB->>DBB: Store session locally<br/>Derive same HMAC key
    InstB->>DBB: UPDATE status → proposing
    deactivate InstB

    Note over ManagerA,ManagerB: Phase 3: Proposal Exchange (Round 1)

    InstA->>DBA: Query availability_windows<br/>for all Team A members
    activate InstA
    InstA->>InstA: SchedulingService<br/>.findAvailableWindows()
    InstA->>DBA: INSERT negotiation_proposals<br/>(round 1, initiator, top 5 slots)
    InstA->>InstB: POST /api/negotiate/{id}/relay<br/>HMAC-signed proposal
    deactivate InstA

    activate InstB
    InstB->>InstB: Validate HMAC signature<br/>+ timestamp drift < 5min
    InstB->>DBB: Query availability_windows<br/>for all Team B members
    InstB->>InstB: SchedulingService<br/>.findAvailableWindows()
    InstB->>InstB: SchedulingService<br/>.intersectWindows()

    alt Match Found
        InstB->>DBB: UPDATE status → pending_approval
        InstB->>InstA: Relay: match_found<br/>{matched slot}
        InstB->>ManagerB: WebSocket: match_found<br/>Push notification
        InstA->>ManagerA: WebSocket: match_found<br/>Push notification
    else No Match
        InstB->>DBB: INSERT negotiation_proposals<br/>(round 1, responder, counter slots)
        InstB->>InstA: Relay: counter_proposal<br/>{top 5 Team B slots}
        Note over InstA,InstB: Repeat for rounds 2..max_rounds
    end
    deactivate InstB

    Note over ManagerA,ManagerB: Phase 4: Confirmation

    ManagerA->>InstA: POST /negotiations/{id}/confirm<br/>{slot}
    InstA->>DBA: Record Manager A confirmation

    ManagerB->>InstB: POST /negotiations/{id}/confirm<br/>{slot}
    InstB->>DBB: Record Manager B confirmation
    InstB->>InstA: Relay: both_confirmed

    par Create Events
        InstA->>DBA: INSERT events (game)<br/>for Team A
    and
        InstB->>DBB: INSERT events (game)<br/>for Team B
    end

    InstA->>DBA: UPDATE status → confirmed<br/>Set agreed_starts_at
    InstA->>SQS: SEND_NOTIFICATION<br/>(confirmation to all Team A members)
    InstB->>SQS: SEND_NOTIFICATION<br/>(confirmation to all Team B members)

    SQS->>Agent: Draft confirmation messages
    Agent->>Agent: CommunicationAgent<br/>.draftNegotiationOutcome()
    Agent->>ManagerA: Push: "Game confirmed!<br/>Sat Apr 5 at 10am"
    Agent->>ManagerB: Push: "Game confirmed!<br/>Sat Apr 5 at 10am"
```

---

## 3. Scheduling Service — Window Computation Data Flow

How `SchedulingService.findAvailableWindows()` computes optimal meeting times.

```mermaid
flowchart TD
    subgraph Inputs
        AW[availability_windows table<br/>All active team members]
        GC[Google Calendar busy blocks<br/>From last SYNC_CALENDAR]
        EV[events table<br/>Already-scheduled games]
        REQ[Request params<br/>dateRange, duration, preferredDays]
    end

    subgraph "Step 1: Gather Raw Data"
        AW --> FETCH[Fetch all windows<br/>for team members]
        GC --> MERGE[Merge calendar conflicts<br/>source = google_cal]
        FETCH --> MERGE
    end

    subgraph "Step 2: Build Per-Member Timelines"
        MERGE --> TIMELINE[Build free/busy timeline<br/>per member per day]
        EV --> TIMELINE
        TIMELINE --> |"available windows<br/>minus unavailable blocks<br/>minus existing events"| FREE[Per-member<br/>free blocks]
    end

    subgraph "Step 3: Find Team Windows"
        FREE --> CONTIGUOUS[Find contiguous free blocks<br/>≥ durationMinutes]
        REQ --> CONTIGUOUS
        CONTIGUOUS --> SCORE[Score each window:<br/>confidence = members_free / total_members]
    end

    subgraph "Step 4: Rank & Return"
        SCORE --> BOOST[Boost score for<br/>preferred days of week]
        REQ --> BOOST
        BOOST --> TOP10[Return top 10 windows<br/>sorted by score DESC]
    end

    TOP10 --> OUTPUT[List of TimeWindow<br/>startsAt, endsAt, confidence]

    style Inputs fill:#e1f5fe
    style OUTPUT fill:#c8e6c9
```

---

## 4. Cross-Team Window Intersection

How `SchedulingService.intersectWindows()` finds mutually available times.

```mermaid
flowchart LR
    subgraph "Instance A"
        A_AW[Team A<br/>availability_windows] --> A_SS[SchedulingService<br/>.findAvailableWindows]
        A_SS --> A_WIN["Team A Windows<br/>[Sat 9-12, Sun 2-5, ...]"]
    end

    subgraph "Instance B"
        B_AW[Team B<br/>availability_windows] --> B_SS[SchedulingService<br/>.findAvailableWindows]
        B_SS --> B_WIN["Team B Windows<br/>[Sat 10-1, Sat 3-5, ...]"]
    end

    A_WIN --> INTERSECT["intersectWindows()<br/>Find overlapping ranges<br/>where both teams have<br/>confidence ≥ 0.5"]
    B_WIN --> INTERSECT

    INTERSECT --> RESULT["Matched Windows<br/>[Sat 10-12 (conf: 0.85)]"]

    RESULT --> |"Match found"| APPROVE[pending_approval<br/>Both managers confirm]
    RESULT --> |"No match"| COUNTER[Counter-proposal<br/>Next round]

    style INTERSECT fill:#fff9c4
    style RESULT fill:#c8e6c9
```

---

## 5. System Architecture — Component Overview

High-level view of all components and how they communicate.

```mermaid
flowchart TB
    subgraph Mobile["Mobile App (iOS / Expo)"]
        LOGIN[Login Screen<br/>OTP Auth]
        SCHED[Schedule Feed<br/>Events List]
        NEG[Negotiation<br/>Approval Screen]
        SETTINGS[Settings<br/>Calendar Connect]
    end

    subgraph Backend["Kotlin Spring Boot (Instance A)"]
        AUTH[Auth Controller<br/>OTP + JWT]
        TEAM_API[Team Controller<br/>CRUD + Roster]
        EVENT_API[Event Controller<br/>CRUD + RSVP]
        NEG_API[Negotiation Controller<br/>Protocol Endpoints]
        RELAY[Cross-Instance Relay<br/>HMAC Auth]
        WS[WebSocket Server<br/>Real-time Updates]
        TAG[TeamAccessGuard<br/>Multi-tenancy]
        SS[SchedulingService<br/>Window Computation]
        NS[NegotiationService<br/>State Machine]
    end

    subgraph BackendB["Kotlin Spring Boot (Instance B)"]
        RELAY_B[Cross-Instance Relay<br/>HMAC Auth]
        NS_B[NegotiationService<br/>State Machine]
    end

    subgraph AgentLayer["Agent Layer (Node.js/TS)"]
        CAL_SYNC[Calendar Sync<br/>Worker]
        COMM[CommunicationAgent<br/>Claude Haiku]
        NOTIF[Notification<br/>Worker]
    end

    subgraph Infra["Infrastructure"]
        PG_A[(Postgres A<br/>:5432)]
        PG_B[(Postgres B<br/>:5433)]
        REDIS[(Redis<br/>:6379)]
        SQS_Q[SQS Queues<br/>LocalStack :4566]
        GCAL[Google Calendar<br/>API]
        HAIKU[Claude Haiku<br/>API]
    end

    LOGIN -->|"POST /auth/*"| AUTH
    SCHED -->|"GET /teams/:id/events"| EVENT_API
    NEG -->|"POST /negotiations/*"| NEG_API
    SETTINGS -->|"POST /users/me/calendar/connect"| AUTH

    NEG_API --> TAG
    TEAM_API --> TAG
    EVENT_API --> TAG
    TAG -->|"verify membership"| PG_A

    NEG_API --> NS
    NS --> SS
    SS -->|"query availability"| PG_A
    NS -->|"relay proposals"| RELAY
    RELAY <-->|"HMAC-signed HTTP"| RELAY_B
    RELAY_B --> NS_B
    NS_B -->|"query availability"| PG_B

    WS -->|"push updates"| NEG

    NS -->|"enqueue tasks"| SQS_Q
    SQS_Q -->|"poll"| CAL_SYNC
    SQS_Q -->|"poll"| NOTIF
    SQS_Q -->|"poll"| COMM

    CAL_SYNC -->|"FreeBusy API"| GCAL
    CAL_SYNC -->|"write windows"| PG_A
    COMM -->|"draft messages"| HAIKU
    NOTIF -->|"push/SMS"| Mobile

    AUTH -->|"rate limits"| REDIS
    NS -->|"nonce replay protection"| REDIS

    style Mobile fill:#e3f2fd
    style Backend fill:#e8f5e9
    style BackendB fill:#e8f5e9
    style AgentLayer fill:#fff3e0
    style Infra fill:#f3e5f5
```

---

## 6. Authentication Flow — OTP + JWT

Passwordless login flow from mobile app to backend.

```mermaid
sequenceDiagram
    actor User as User (Mobile)
    participant App as Mobile App
    participant API as Backend API
    participant Redis as Redis
    participant DB as Postgres
    participant SQS as SQS
    participant Agent as Agent Layer

    User->>App: Enter phone number
    App->>API: POST /auth/request-otp<br/>{channel: "sms", value: "+1202..."}

    activate API
    API->>Redis: Check rate limit<br/>(3 per 15min, 10 per 24h)

    alt Rate limited
        API-->>App: 429 Too Many Requests
    else Allowed
        API->>API: Generate 6-digit OTP
        API->>DB: INSERT auth_tokens<br/>(token_hash, expires: +10min)
        API->>DB: INSERT/UPDATE otp_rate_limits<br/>(audit trail)
        API->>SQS: SEND_NOTIFICATION<br/>{type: "otp", phone, otp}
        API-->>App: 200 {message: "OTP sent"}
    end
    deactivate API

    SQS->>Agent: Deliver OTP via SMS/email
    Agent->>User: SMS: "Your FieldIQ code: 482901"

    User->>App: Enter OTP code
    App->>API: POST /auth/verify-otp<br/>{channel: "sms", value: "+1202...", otp: "482901"}

    activate API
    API->>Redis: Check failed attempts<br/>(max 5, then block 1h)
    API->>DB: SELECT auth_tokens<br/>WHERE token_hash = hash(otp)

    alt Invalid OTP
        API->>Redis: Increment failed count
        API-->>App: 401 Invalid OTP
    else Valid OTP
        API->>DB: UPDATE auth_tokens<br/>SET used_at = now()
        API->>DB: UPSERT users<br/>(create if first login)
        API->>API: Generate JWT (15min)<br/>Generate refresh token (30d)
        API->>DB: INSERT refresh_tokens<br/>(token_hash, expires, device_info)
        API-->>App: 200 {accessToken, refreshToken, user}
    end
    deactivate API

    App->>App: Store tokens in SecureStore
```

---

## 7. Calendar Sync — Data Flow

How Google Calendar busy blocks become availability windows.

```mermaid
sequenceDiagram
    participant SQS as SQS Queue
    participant Worker as Calendar Sync Worker
    participant DB as Postgres
    participant GCal as Google Calendar API

    Note over SQS,GCal: Triggered: on calendar connect + every 4 hours

    SQS->>Worker: SYNC_CALENDAR task<br/>{userId, teamId}
    activate Worker

    Worker->>DB: SELECT calendar_integrations<br/>WHERE user_id = ?
    DB-->>Worker: {access_token, refresh_token,<br/>expires_at, last_synced_at}

    alt Token expired
        Worker->>GCal: POST /token (refresh)
        GCal-->>Worker: New access_token
        Worker->>DB: UPDATE calendar_integrations<br/>SET access_token, expires_at
    end

    Worker->>GCal: POST /freeBusy<br/>{timeMin: now, timeMax: +30 days,<br/>calendars: [primary]}
    GCal-->>Worker: {busy: [{start, end}, ...]}

    Worker->>Worker: Convert busy blocks to<br/>AvailabilityWindow objects<br/>(type: unavailable, source: google_cal)

    Worker->>DB: DELETE availability_windows<br/>WHERE user_id = ? AND team_id = ?<br/>AND source = 'google_cal'
    Worker->>DB: INSERT availability_windows<br/>(fresh busy blocks)
    Worker->>DB: UPDATE calendar_integrations<br/>SET last_synced_at = now()

    deactivate Worker
```

---

## 8. HMAC Cross-Instance Authentication

How two FieldIQ instances authenticate relay requests.

```mermaid
sequenceDiagram
    participant InstA as Instance A
    participant InstB as Instance B
    participant RedisB as Redis (Instance B)

    Note over InstA,InstB: Setup: Both derive same session key<br/>sessionKey = HMAC-SHA256(instanceSecret, inviteToken)

    InstA->>InstA: Build request body (JSON)
    InstA->>InstA: message = sessionId + timestamp + body
    InstA->>InstA: signature = HMAC-SHA256(sessionKey, message)

    InstA->>InstB: POST /api/negotiate/{id}/relay<br/>X-FieldIQ-Session-Id: {uuid}<br/>X-FieldIQ-Timestamp: {ISO-8601}<br/>X-FieldIQ-Signature: {hex}

    activate InstB
    InstB->>InstB: Check timestamp drift < 5 minutes

    alt Timestamp too old
        InstB-->>InstA: 401 {error: "expired"}
    end

    InstB->>InstB: Recompute signature from<br/>own copy of sessionKey
    InstB->>InstB: Compare signatures

    alt Signature mismatch
        InstB-->>InstA: 401 {error: "invalid_signature"}
    end

    InstB->>RedisB: SET nonce:{hash(sig)} 1 EX 300 NX
    Note over RedisB: NX = only set if not exists<br/>Prevents replay within 5min window

    alt Nonce exists (replay)
        InstB-->>InstA: 401 {error: "replay_detected"}
    end

    InstB->>InstB: Validate state transition
    InstB->>InstB: Process relay request
    InstB-->>InstA: 200 {status: "received"}
    deactivate InstB
```

---

## 9. Notification Dispatch — Event Reminder Flow

How the system sends reminders 24h and 2h before events.

```mermaid
flowchart TD
    subgraph Backend
        SCHED_JOB[Scheduled Job<br/>Check events in next 24h/2h] --> ENQUEUE[Enqueue SQS tasks<br/>SEND_REMINDERS]
    end

    subgraph Agent["Agent Layer"]
        ENQUEUE --> POLL[Worker polls SQS]
        POLL --> FETCH[Fetch event details<br/>+ team roster from DB]
        FETCH --> FILTER[Filter: who hasn't<br/>responded yet?]
        FILTER --> HAIKU[CommunicationAgent<br/>drafts personalized message<br/>via Claude Haiku]
        HAIKU --> DISPATCH{Dispatch Channel}
    end

    DISPATCH -->|"has Expo token"| PUSH[Expo Push<br/>Notification]
    DISPATCH -->|"phone on file"| SMS[SMS via Twilio]
    DISPATCH -->|"email on file"| EMAIL[Email via SendGrid]

    PUSH --> PHONE[User's Phone]
    SMS --> PHONE
    EMAIL --> INBOX[User's Inbox]

    style Backend fill:#e8f5e9
    style Agent fill:#fff3e0
```
