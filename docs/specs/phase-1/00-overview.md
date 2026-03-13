# FieldIQ -- Phase 1 Overview
**Target: Working cross-team scheduling negotiation demo + iOS MVP**
**Timeline: Months 1-4 (16 weeks) | Stack: Kotlin + Node.js/TS + React Native Expo + Docker**
**Last updated: 2026-03-13**

---

## Sub-Documents (in implementation order)

| # | Document | Sprints | Contents |
|---|----------|---------|----------|
| 01 | [Schema](01-schema.md) | 1 | All SQL migrations (V1, V2, V3) + schema notes |
| 02 | [Auth & Calendar](02-auth-calendar.md) | 2-3 | OTP flow, Google Calendar OAuth, token encryption |
| 03 | [Backend](03-backend.md) | 2-4 | Gradle deps, multi-tenancy, REST API, WebSocket, SchedulingService |
| 04 | [Negotiation Protocol](04-negotiation-protocol.md) | 4 | Protocol flow, state machine, HMAC auth, relay contract, error handling |
| 05 | [Agent Layer](05-agent-layer.md) | 3-4 | SQS workers, calendar sync, CommunicationAgent |
| 06 | [Mobile](06-mobile.md) | 5-6 | Expo screens, negotiation UX, API client |
| 07 | [CI & Testing](07-ci-testing.md) | 1+ | GitHub Actions, CI hardening, testing strategy |

---

## Changelog from v1

- Resolved calendar scope inconsistency: Phase 1 is read-only + `.ics` export, no calendar write-back
- Added **Architectural Decisions** section locking down scheduling ownership, cross-instance auth, and two-instance dev strategy
- Added `refresh_tokens`, `user_devices`, and `event_responses` tables
- Added `availability_windows` check constraints (exactly one of `day_of_week`/`specific_date`, `start_time < end_time`)
- Added `opponent_name` + `opponent_external_ref` to `events` for cross-instance opponents
- Added cross-instance **protocol contract** with request/response payloads, HMAC signatures, and idempotency
- Added multi-tenancy enforcement strategy
- Added OTP rate limiting design
- Added vertical slice milestone at end of Sprint 4
- Revised sprint breakdown with clearer dependencies
- Updated north star test to match Phase 1 scope (no calendar write-back)
- Added CI hardening notes (Flyway in tests, mock Redis/AWS)

---

## Architectural Decisions (Locked)

These decisions must be made before Sprint 1 ends. They are recorded here to prevent rework.

### Decision 1: Scheduling logic lives in the Kotlin backend

The `SchedulingAgent` (availability computation, window ranking, intersection) is implemented as a **Kotlin service** in the backend, not in the TypeScript agent layer.

**Rationale:** One codebase for all scheduling logic. The backend already has DB access, and the scheduling algorithm is deterministic (no LLM). The agent layer focuses on what it's good at: async SQS workers for calendar sync, LLM-based message drafting, and notification dispatch.

**Boundary:**
- Backend owns: `SchedulingService.kt` (window computation), `NegotiationService.kt` (protocol orchestration), all REST endpoints
- Agent layer owns: `SYNC_CALENDAR` worker (Google Calendar polling), `CommunicationAgent` (Haiku message drafting), `SEND_NOTIFICATION` worker (push/SMS dispatch)
- Backend enqueues tasks to SQS; agent layer consumes them. Agent layer never calls backend endpoints directly -- it reads/writes to the shared DB for results.

### Decision 2: Cross-instance auth uses HMAC signatures

The `invite_token` is a single-use bearer secret with a short TTL (48 hours). After the responder joins, all subsequent cross-instance calls are authenticated with HMAC:

- `X-FieldIQ-Session-Id`: the negotiation session UUID
- `X-FieldIQ-Timestamp`: ISO-8601 UTC timestamp (reject if >5 min drift)
- `X-FieldIQ-Signature`: HMAC-SHA256 over `session_id + timestamp + request_body` using a per-session derived key

The per-session key is derived: `HMAC-SHA256(instance_secret, invite_token)`. Both instances compute this independently after the join handshake.

**Phase 1 scope:** Implement the signature scheme. Do not implement certificate pinning or mTLS -- that's Phase 2+ if FieldIQ instances are deployed across trust boundaries.

### Decision 3: Two-instance local dev uses two Spring Boot processes

`docker-compose.yml` provides `postgres` (port 5432) and `postgres-team-b` (port 5433). Two Spring Boot instances run on ports 8080 and 8081, each with its own DB and `FIELDIQ_INSTANCE_ID`.

**How to run locally:**
```bash
# Terminal 1: Instance A
SPRING_PROFILES_ACTIVE=instance-a ./gradlew bootRun

# Terminal 2: Instance B
SPRING_PROFILES_ACTIVE=instance-b ./gradlew bootRun
```

`application-instance-a.yml` and `application-instance-b.yml` override DB URL, server port, and instance ID. Both share the same Redis and LocalStack.

For automated integration tests: use a single Spring Boot test context with two `NegotiationService` instances wired to different `DataSource` beans, simulating cross-instance calls as in-process method calls. This avoids flaky HTTP-based tests in CI.

### Decision 4: No calendar write-back in Phase 1

Google Calendar integration is read-only (Free/Busy via `calendar.readonly` scope). When a game is confirmed:
1. Event is created in FieldIQ's `events` table
2. Push notification + SMS sent to both teams
3. An "Add to Calendar" `.ics` download link is included in the notification

Calendar write-back (creating Google Calendar events automatically) requires write scope, increases OAuth complexity, and raises user trust concerns. Deferred to Phase 2.

### Decision 5: Shared types strategy

There is no automated code generation between Kotlin and TypeScript in Phase 1. The `shared/types/` directory contains **TypeScript interfaces** that define the API contract. Kotlin data classes are maintained manually to match. This is acceptable for a sole developer in Phase 1.

**Discipline required:** When changing an API response shape, update both the Kotlin DTO and the corresponding TypeScript interface in the same commit.

---

## Repo Structure

Before writing a single line of application code, establish the monorepo layout. Everything lives under `vdefran21/fieldiq`.

```
fieldiq/
+-- CLAUDE.md                        <- Claude Code context (create Day 1)
+-- docker-compose.yml               <- Full local dev environment
+-- docker-compose.test.yml          <- Isolated test DB for CI
+-- .env.example                     <- Committed env template (no secrets)
+-- .env.local                       <- Git-ignored, your actual dev values
+-- .github/
|   +-- workflows/
|       +-- backend-ci.yml
|       +-- agent-ci.yml
|       +-- mobile-ci.yml
+-- backend/                         <- Kotlin Spring Boot API
|   +-- build.gradle.kts
|   +-- src/main/kotlin/com/fieldiq/
|   |   +-- FieldIQApplication.kt
|   |   +-- config/
|   |   +-- domain/                  <- Data models / entities
|   |   +-- repository/              <- Spring Data JPA repos
|   |   +-- service/                 <- Business logic (incl. SchedulingService)
|   |   +-- api/                     <- REST controllers
|   |   +-- negotiation/             <- Cross-team protocol (core IP)
|   |   +-- security/                <- Auth filters, HMAC validation, rate limiting
|   |   +-- websocket/               <- Real-time event push
|   +-- src/main/resources/
|   |   +-- application.yml
|   |   +-- application-instance-a.yml
|   |   +-- application-instance-b.yml
|   |   +-- db/migration/            <- Flyway migrations (V1__, V2__, V3__)
|   +-- src/test/
+-- agent/                           <- Node.js/TypeScript AI agent layer
|   +-- package.json
|   +-- tsconfig.json
|   +-- src/
|   |   +-- index.ts
|   |   +-- workers/
|   |   |   +-- calendar-sync.worker.ts   <- SYNC_CALENDAR SQS consumer
|   |   |   +-- notification.worker.ts    <- SEND_NOTIFICATION SQS consumer
|   |   |   +-- reminder.worker.ts        <- SEND_REMINDERS SQS consumer
|   |   +-- services/
|   |   |   +-- communication.agent.ts    <- RSVP/reminder drafting via Claude Haiku
|   |   +-- integrations/
|   |   |   +-- google-calendar.ts
|   |   |   +-- anthropic.ts              <- Claude API client (Haiku by default)
|   |   +-- types/
|   +-- tests/
+-- mobile/                          <- React Native (Expo)
|   +-- app.json
|   +-- package.json
|   +-- app/                         <- Expo Router file-based routing
|   |   +-- (auth)/
|   |   |   +-- login.tsx
|   |   +-- (app)/
|   |   |   +-- index.tsx            <- Home / schedule feed
|   |   |   +-- team.tsx             <- Roster view
|   |   |   +-- negotiate.tsx        <- Negotiation approval screen
|   |   |   +-- settings.tsx
|   |   +-- _layout.tsx
|   +-- components/
|   +-- services/
|       +-- api.ts                   <- Backend API client
+-- shared/
    +-- types/                       <- TypeScript API contract types
```

---

## Week 1 -- Local Environment + Skeleton

### 1. `docker-compose.yml`

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

`infra/localstack-init.sh` -- auto-creates SQS queues on startup:
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

# Cross-instance HMAC secret (each instance has its own)
FIELDIQ_INSTANCE_SECRET=dev-instance-secret-change-in-production

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
TWILIO_ACCOUNT_SID=     # For SMS OTP -- leave blank to use email in dev
TWILIO_AUTH_TOKEN=
SENDGRID_API_KEY=       # For email magic link in dev
```

---

## Sprint Breakdown -- 16 Weeks

```
Sprint 1  (Weeks 1-2)   FOUNDATION
                         Repo setup, Docker Compose (both Postgres instances verified),
                         data model V1 + V2 + V3 Flyway migrations,
                         CLAUDE.md, backend skeleton running against local Postgres,
                         application-instance-a.yml + instance-b.yml profiles,
                         verify: both instances boot and run migrations independently

Sprint 2  (Weeks 3-4)   CORE CRUD + AUTH
                         Auth layer (OTP + JWT + refresh tokens + rate limiting),
                         TeamAccessGuard (multi-tenancy enforcement),
                         team + member CRUD, availability window CRUD,
                         basic event CRUD + RSVP responses,
                         user_devices registration endpoint,
                         unit tests for all new services

Sprint 3  (Weeks 5-6)   SCHEDULING + CALENDAR SYNC
                         Google Calendar OAuth + token storage,
                         calendar sync agent worker (SQS + LocalStack),
                         SchedulingService v1 (deterministic window finder in Kotlin),
                         POST /suggest-windows endpoint working,
                         cross-instance relay client scaffolding (WebFlux HTTP client
                         with HMAC signature generation/validation)

Sprint 4  (Weeks 7-8)   NEGOTIATION PROTOCOL v1 [HARD TIME-BOX: 2 WEEKS MAX]
                         NegotiationService + all DB operations,
                         /negotiations endpoints (initiate, join, propose, respond, confirm),
                         /api/negotiate/* cross-instance relay endpoints with HMAC auth,
                         CrossInstanceRelayClient with retry + idempotency,
                         full round-trip integration test with two service instances

                         SCOPE PRIORITY: Happy-path negotiation flow ONLY.
                         Defer preferred_day_of_week scoring to Sprint 6 if needed --
                         basic date-range intersection is sufficient for the vertical slice.

                         *** VERTICAL SLICE MILESTONE (end of Sprint 4) ***
                         Verify via curl/Postman (no mobile yet):
                         1. Manager A initiates negotiation on Instance A
                         2. Manager B joins on Instance B via invite_token
                         3. Proposals exchange automatically for up to 3 rounds
                         4. Match found -> both managers confirm -> events created
                         If this doesn't work end-to-end, do NOT proceed to mobile.
                         Fix the protocol first.

                         CONTINGENCY: If the vertical slice is not 100% working by
                         end of week 8, cut WebSocket real-time updates to HTTP polling
                         in Sprint 6. Add WebSocket back in Sprint 7 integration phase.

Sprint 5  (Weeks 9-10)  REACT NATIVE APP
                         Expo project setup, Expo Router,
                         Login screen + OTP flow + SecureStore token management,
                         Schedule feed (events list), Team/roster screen,
                         Settings screen + Google Calendar connect flow,
                         API client (services/api.ts),
                         push token registration on app launch,
                         Lottie or Animated "Finding mutual time..." component
                         for negotiation screen (prep for Sprint 6 UX)

Sprint 6  (Weeks 11-12) NEGOTIATION UX + NOTIFICATIONS
                         Negotiation approval screen (the key UX moment),
                         WebSocket client for real-time negotiation updates,
                         push notifications via Expo (FCM for iOS),
                         CommunicationAgent (Haiku) for reminder + outcome drafting,
                         RSVP tracking UI on event detail screen,
                         .ics download for confirmed games

Sprint 7  (Weeks 13-14) END-TO-END INTEGRATION
                         Two managers on two physical iOS devices,
                         full negotiation: initiate -> proposals -> confirm -> event,
                         push notifications arrive on both devices,
                         fix everything that breaks, polish the happy path,
                         verify fallback flow (Team B not on FieldIQ)

Sprint 8  (Weeks 15-16) REAL USERS + INSTRUMENTATION
                         Invite 5 real DMV soccer managers (your wife + 4),
                         instrument time-saved metric (time from initiate to confirmed),
                         fix friction points from real usage,
                         deploy to AWS (ECS Fargate + RDS + ElastiCache + SQS),
                         estimated infra cost: ~$80-120/mo for 20-team beta
                         (see business plan infra section for breakdown),
                         prepare for 15-team beta launch (Phase 2)
```

---

## What NOT to Build in Phase 1

Resist scope creep. These are explicitly out of scope:

- No Stripe / payment processing -- Phase 2
- No club admin dashboard -- Phase 2
- No tournament scheduling -- Phase 3
- No Android app -- Phase 2 (iOS only)
- No field booking API integrations -- manual field entry is fine
- No multilingual support -- post-beta
- No volunteer/snack rotation -- post-beta
- No player performance tracking -- not FieldIQ's product
- No Google Calendar write-back -- Phase 2 (read-only + .ics export in Phase 1)
- No Postgres Row Level Security -- Phase 2 hardening (service-layer guards in Phase 1)
- No mTLS/certificate pinning for cross-instance calls -- Phase 2

Every feature not listed above is a distraction from the one thing
that matters in Phase 1: **prove the cross-team negotiation protocol works
with real people on real devices.**
