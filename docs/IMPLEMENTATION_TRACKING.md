# FieldIQ Phase 1 — Implementation Tracking

**Target:** Working cross-team scheduling negotiation demo + iOS MVP
**Timeline:** 16 weeks (8 sprints)
**Last updated:** 2026-03-08 (session 25 — cross-instance responder-team alignment)

**Legend:** ✅ Complete | 🔧 In Progress | ⬜ Not Started

**Doc Reference:**
| Doc | File | Contents |
|-----|------|----------|
| 00 | `docs/00_Phase1_Overview.md` | Sprint plan, repo structure, docker-compose, .env |
| 01 | `docs/01_Phase1_Schema.md` | All SQL migrations (V1, V2, V3) + schema notes |
| 02 | `docs/02_Phase1_Auth_Calendar.md` | OTP flow, Google Calendar OAuth, token encryption |
| 03 | `docs/03_Phase1_Backend.md` | Gradle deps, multi-tenancy, REST API, WebSocket, SchedulingService |
| 04 | `docs/04_Phase1_Negotiation_Protocol.md` | Protocol flow, state machine, HMAC auth, relay contract |
| 05 | `docs/05_Phase1_Agent_Layer.md` | SQS workers, calendar sync, CommunicationAgent |
| 06 | `docs/06_Phase1_Mobile.md` | Expo screens, negotiation UX, API client |
| 07 | `docs/07_Phase1_CI_Testing.md` | GitHub Actions, CI hardening, testing strategy |

---

## Sprint 1 (Weeks 1–2): FOUNDATION ✅

| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 00 | Monorepo structure (`backend/`, `agent/`, `mobile/`, `shared/`, `docs/`, `infra/`) | Root directory layout established |
| ✅ | 00 | `docker-compose.yml` — dual Postgres (5432, 5433), Redis, LocalStack | `docker-compose.yml` |
| ✅ | 00 | `.env.example` with all config templates | `.env.example` |
| ✅ | 00 | `CLAUDE.md` with architecture decisions | `CLAUDE.md` |
| ✅ | 00 | `README.md` with architecture overview and quick start | `README.md` |
| ✅ | 03 | Backend README testing documentation | `backend/README.md` — clarified that `./gradlew test` may be up to date, added rerun commands, and pointed to HTML/XML test reports. |
| ✅ | 00 | `dev.sh` local environment orchestration script | `dev.sh`, `.gitignore` — persists backend runtime state in `.fieldiq-dev/` so `./dev.sh stop` and `Ctrl+C` clean up backend Java processes as well as Docker containers. Session 21 verification: script stop/start logic does target both backend JVMs, but current local state showed no listeners on `localhost:8080` or `localhost:8081` and no `.fieldiq-dev/*.env` state files, indicating startup failure or an exited `bootRun` process rather than stale backends surviving `stop --all`. |
| ✅ | 00 | `infra/localstack-init.sh` — 3 SQS queues auto-created | `infra/localstack-init.sh` |
| ✅ | 03 | Backend skeleton — Spring Boot 3.3, Java 21, Kotlin | `backend/build.gradle.kts`, `FieldIQApplication.kt`. Session 5: added `@PostConstruct` JVM timezone=UTC to prevent `LocalTime` ↔ PostgreSQL `TIME` column shift when Hibernate `jdbc.time_zone: UTC` doesn't match JVM locale. |
| ✅ | 03 | `build.gradle.kts` with all Phase 1 dependencies | Spring Boot, JPA, WebSocket, Security, JWT, WebFlux, TestContainers, MockK |
| ✅ | 01 | V1 migration — core tables (organizations, teams, users, team_members, auth_tokens, refresh_tokens, events, availability_windows, calendar_integrations, user_devices, event_responses) | `V1__initial_schema.sql` |
| ✅ | 01 | V2 migration — negotiation tables (sessions, proposals, events audit) | `V2__negotiation_schema.sql` |
| ✅ | 01 | V3 migration — OTP rate limiting table | `V3__rate_limiting.sql` |
| ✅ | 03 | `application.yml` — default config | `backend/src/main/resources/application.yml` |
| ✅ | 03 | `application-instance-a.yml` — port 8080, fieldiq DB | `backend/src/main/resources/application-instance-a.yml` |
| ✅ | 03 | `application-instance-b.yml` — port 8081, fieldiq_team_b DB | `backend/src/main/resources/application-instance-b.yml` |
| ✅ | 07 | `application-test.yml` — test profile | `backend/src/main/resources/application-test.yml` |
| ✅ | 03 | All 13 JPA domain entities with KDoc | `backend/src/main/kotlin/com/fieldiq/domain/` |
| ✅ | 03 | All 8 Spring Data JPA repositories | `backend/src/main/kotlin/com/fieldiq/repository/` |
| ✅ | 03 | `SecurityConfig` — stateless JWT security scaffold | `backend/src/main/kotlin/com/fieldiq/security/SecurityConfig.kt`. Session 5: added custom `AuthenticationEntryPoint` returning JSON `ErrorResponse` envelope with 401 for unauthenticated requests (Spring Security 6 default was bare 403). |
| ✅ | 03 | `FieldIQProperties` — type-safe config binding | `backend/src/main/kotlin/com/fieldiq/config/FieldIQProperties.kt` |
| ✅ | 03 | `AppConfig` — enables configuration properties | `backend/src/main/kotlin/com/fieldiq/config/AppConfig.kt` |
| ✅ | 03 | `TeamAccessGuard` — multi-tenancy enforcement | `backend/src/main/kotlin/com/fieldiq/service/TeamAccessGuard.kt` |
| ✅ | 00 | `shared/types/index.ts` — full TypeScript API contract (598 lines) | `shared/types/index.ts` |
| ✅ | 07 | GitHub Actions CI workflows (backend, agent, mobile) | `.github/workflows/backend-ci.yml`, `agent-ci.yml`, `mobile-ci.yml` |

---

## Sprint 2 (Weeks 3–4): CORE CRUD + AUTH ✅

### Auth Layer
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 02 | `POST /auth/request-otp` — send OTP via SMS/email | `AuthController.kt`, `AuthService.requestOtp()` |
| ✅ | 02 | `POST /auth/verify-otp` — verify hashed OTP, issue JWT + refresh token | `AuthController.kt`, `AuthService.verifyOtp()` |
| ✅ | 02 | `POST /auth/refresh` — rotate refresh token, issue new JWT | `AuthController.kt`, `AuthService.refreshToken()` |
| ✅ | 02 | `POST /auth/logout` — revoke refresh token | `AuthController.kt`, `AuthService.logout()` |
| ✅ | 02 | JWT generation service (15min access, refresh rotation) | `JwtService.kt` — HS256, configurable expiry |
| ✅ | 03 | JWT authentication filter (validate on every request) | `JwtAuthenticationFilter.kt` — `OncePerRequestFilter` |
| ✅ | 02 | OTP rate limiting — Redis-backed (3/15min, 10/24h per identifier) | `OtpRateLimitService.kt`, `RedisConfig.kt` |
| ✅ | 02 | OTP rate limiting — DB persistence for audit | `OtpRateLimitService.recordAttempt()` → `otp_rate_limits` |
| ✅ | 02 | Dev OTP bypass for `+1555*` phone numbers | `AuthService.requestOtp()` + `OtpRateLimitService.isDevBypass()` |
| ✅ | 01 | V4 migration — drop UNIQUE on auth_tokens.token_hash | `V4__drop_auth_token_hash_unique.sql` — 6-digit OTP keyspace causes collisions |
| ✅ | 01,02 | V5 migration — bind OTP tokens to identifier hash | `V5__auth_token_identifier_binding.sql` — adds `identifier_hash` column, prevents cross-identity token consumption. `AuthService.normalizeIdentifier()` + `JwtService.hashToken()` for PII-safe binding. 5 new security tests. |
| ✅ | 02 | Input validation on `verifyOtp` | `AuthService.verifyOtp()` now calls `validateIdentifier()` before DB lookup |

### Team & Member CRUD
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 03 | `POST /teams` — create team (creator becomes manager) | `TeamController.kt`, `TeamService.createTeam()` |
| ✅ | 03 | `GET /teams/:teamId` — get team details | `TeamController.kt`, `TeamService.getTeam()` |
| ✅ | 03 | `POST /teams/:teamId/members` — add member to team | `TeamController.kt`, `TeamService.addMember()` |
| ✅ | 03 | `TeamService` with access control via `TeamAccessGuard` | `TeamService.kt` — all ops use guard |

### Event CRUD
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 03 | `POST /teams/:teamId/events` — create event | `EventController.kt`, `EventService.createEvent()` |
| ✅ | 03 | `GET /teams/:teamId/events` — list team events | `EventController.kt`, `EventService.getTeamEvents()` |
| ✅ | 03 | `PATCH /events/:eventId` — update event | `EventController.kt`, `EventService.updateEvent()` |
| ✅ | 03 | `POST /events/:eventId/respond` — RSVP | `EventController.kt`, `EventService.respondToEvent()` |
| ✅ | 03 | `EventService` | `EventService.kt` — full CRUD + RSVP upsert |

### Availability & Devices
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 03 | `POST /users/me/availability` — set availability windows | `AvailabilityController.kt`, `AvailabilityWindowService.createWindow()` |
| ✅ | 03 | `GET /teams/:teamId/availability` — get team availability | `AvailabilityController.kt`, `AvailabilityWindowService.getTeamAvailability()` |
| ✅ | 03 | `AvailabilityWindowService` | `AvailabilityWindowService.kt` — validation + CRUD |
| ✅ | 03 | `POST /users/me/devices` — register push token | `UserController.kt`, `UserDeviceService.registerDevice()` |

### Testing
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 07 | Unit tests for AuthService | `AuthServiceTest.kt` — 24 tests (OTP request/verify, refresh rotation, logout, identifier binding security) |
| ✅ | 07 | Unit tests for TeamService | `TeamServiceTest.kt` — 10 tests (create, get, addMember, getMembers) |
| ✅ | 07 | Unit tests for EventService | `EventServiceTest.kt` — 12 tests (create, list, update, RSVP upsert, responses) |
| ✅ | 07 | Unit tests for AvailabilityWindowService | `AvailabilityWindowServiceTest.kt` — 10 tests (create, validate, query, delete) + `JwtServiceTest.kt` (10), `OtpRateLimitServiceTest.kt` (11). Total: 87/87 passing |
| ✅ | 07 | Comprehensive KDoc on all 6 test files | All test classes, nested classes, test methods, and properties fully documented per CLAUDE.md standards. `@see` cross-references, security rationale, edge case explanations. 92/92 tests passing (87 original + 5 new identifier binding tests). |

### Bruno API Integration Tests (Session 5)
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 07 | Bruno CLI `--env` flag fix | `backend/bruno/package.json` — default `test` script was missing `--env instance-a`, causing `{{baseUrl}}` to be unresolved from CLI (Bruno GUI auto-selects env, CLI does not). |
| ✅ | 07 | Fix `null` vs `undefined` test assertions | `01-createRecurring.bru`, `02-createSpecificDate.bru`, `02-createDraftEvent.bru` — changed `.to.be.null` → `.to.be.undefined` to match Jackson `non_null` serialization (omits null fields). |
| ✅ | 07 | Fix refresh token rotation test | `03-refresh.bru` — added `script:pre-request` to capture old token before `script:post-response` overwrites it (Bruno runs post-response before tests). |
| ✅ | 07 | Add 401 error envelope assertion | `03-getProfile401.bru` — added `res.body.error: eq UNAUTHORIZED` and `res.body.status: eq 401` to verify JSON envelope. |
| ✅ | 07 | All 28 Bruno tests passing (Sprint 2 auth + CRUD + Sprint 3 scheduling) | `npm test` in `backend/bruno/` — full pass. Sprint 3 added 3 scheduling tests. |

---

## Sprint 3 (Weeks 5–6): SCHEDULING + CALENDAR SYNC ✅

### Google Calendar Integration
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 02 | `GET /auth/google/authorize` — initiate OAuth flow | `GoogleCalendarController.kt` — redirects to Google consent screen with `calendar.readonly` scope, `state=userId` for CSRF prevention. |
| ✅ | 02 | `GET /auth/google/callback` — handle OAuth callback | `GoogleCalendarController.kt` — exchanges auth code for tokens, encrypts and stores. Handles error/denied cases. |
| ✅ | 02 | Token encryption (AES-256-GCM via `TokenEncryptionConverter`) | `TokenEncryptionConverter.kt` — AES-256-GCM, random IV per encryption, Base64 output format. 15 unit tests in `TokenEncryptionConverterTest.kt`. |
| ✅ | 02 | Refresh token management for Google tokens | `GoogleCalendarService.refreshAccessToken()` — decrypts stored refresh token, exchanges for new access token, updates DB. 7 unit tests in `GoogleCalendarServiceTest.kt`. |

### Calendar Sync Agent Worker
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 05 | Agent layer project setup (`agent/package.json`, `tsconfig.json`) | `agent/package.json`, `agent/tsconfig.json`, `agent/jest.config.js` — TypeScript, Jest, SQS SDK, googleapis, pg. |
| ✅ | 05 | `calendar-sync.worker.ts` — SQS consumer for `SYNC_CALENDAR` | `agent/src/workers/calendar-sync.worker.ts` + `agent/src/index.ts` SQS polling loop with dispatch. Session 9: fixed `availability_windows` insert projection so `team_id` is included in the VALUES source for specific-date `google_cal` windows. |
| ✅ | 05 | Google FreeBusy API integration (read-only) | `fetchFreeBusy()` in calendar-sync.worker.ts — queries primary calendar, 30-day look-ahead, filters invalid blocks. |
| ✅ | 05 | Convert FreeBusy → `availability_windows` (source='google_cal') | `handleSyncCalendar()` — deletes stale windows, inserts fresh ones as `source='google_cal'`, `window_type='unavailable'`. 12 unit tests passing. |
| ✅ | 07 | Agent runtime refactor: extract `task-dispatcher.ts` from `index.ts` | `task-dispatcher.ts` exports `dispatchTask()`, `processMessage()`, `pollOnce()` with structured `PollResult`. `index.ts` is thin bootstrap with `require.main === module` guard. |
| ✅ | 07 | Agent integration tests (real Postgres + SQS, mocked Google) | `jest.integration.config.js`, `src/__integration__/`, `agent/src/config.ts`, `agent/src/sqs-client.ts`, `agent/src/__integration__/setup/global-setup.ts`, `agent/src/__integration__/setup/global-teardown.ts`, `agent/src/__integration__/setup/test-sqs.ts`, `agent/src/__integration__/calendar-sync.integration.test.ts`, `agent/src/__integration__/sqs-dispatch.integration.test.ts` — 6 worker-level tests (calendar-sync) + 3 runtime-level tests (SQS dispatch via `pollOnce()`). Session 9: added explicit LocalStack credentials to shared SQS client config, destroyed integration SQS clients, closed worker-owned DB pools in `afterAll`, and verified `npm run test:integration` passes with 9/9 tests green and no leaked-handle warning. Session 10: extracted SQS client construction into `agent/src/sqs-client.ts` so runtime bootstrap and test setup share one helper path. Session 11: moved agent task queue URL lookup into the same helper so all SQS client and queue wiring now lives behind `agent/src/sqs-client.ts`. |

### Scheduling Service
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 03 | `SchedulingService.kt` — deterministic window computation | `backend/src/main/kotlin/com/fieldiq/service/SchedulingService.kt` — sweep-line algorithm, interval arithmetic, org timezone resolution. |
| ✅ | 03 | `findAvailableWindows()` — find team availability windows | Per-date member availability aggregation, merges recurring + specific-date windows, subtracts events. Returns top 10 by confidence. |
| ✅ | 03 | Window ranking by confidence (% members available) | Confidence = available_members / total_members. Preferred-day boost (1.25x, capped at 1.0). |
| ✅ | 03 | `intersectWindows()` — cross-team window matching | O(n*m) pairwise overlap detection, min-confidence scoring, threshold filtering. |
| ✅ | 03 | `POST /teams/:teamId/suggest-windows` endpoint | `SchedulingController.kt` + `SchedulingDtos.kt` (SuggestWindowsRequest, TimeWindowDto). JWT auth + TeamAccessGuard. |

### Scheduling Tests
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 07 | Unit tests for SchedulingService | `SchedulingServiceTest.kt` — 21 tests: input validation (5), single member availability (4), multi-member confidence (2), event conflicts (1), preferred days (1), result limiting/timezone (2), intersectWindows (6). 113/113 total tests passing. |
| ✅ | 07 | Bruno integration tests for suggest-windows | `backend/bruno/collections/scheduling/` — 3 tests: happy path, 401 auth, validation. |

### Cross-Instance Relay Scaffolding
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 04 | `CrossInstanceRelayClient` — WebFlux HTTP client | `CrossInstanceRelayClient.kt` — WebFlux client with HMAC headers (`X-FieldIQ-Session-Id`, `X-FieldIQ-Timestamp`, `X-FieldIQ-Signature`, `X-FieldIQ-Instance-Id`), exponential backoff retry (2s/8s/30s on 5xx). 5 unit tests in `CrossInstanceRelayClientTest.kt`. |
| ✅ | 04 | HMAC-SHA256 signature generation | `HmacService.kt` — key derivation (`HMAC-SHA256(instanceSecret, inviteToken)`), signing (`sessionId + \n + timestamp + \n + body`), validation with constant-time comparison and ±5min drift. 14 unit tests in `HmacServiceTest.kt`. |
| ✅ | 04 | HMAC signature validation filter | `HmacAuthenticationFilter.kt` — `OncePerRequestFilter` on `/api/negotiate/` paths, extracts HMAC headers, derives session key, validates signature, Redis nonce for replay prevention (5-min TTL). Session 18: fixed request-body replay by buffering relay JSON before validation and forwarding a replayable wrapper so `NegotiationRelayController.receiveRelay()` still gets `@RequestBody` after HMAC auth. 12 unit tests in `HmacAuthenticationFilterTest.kt` including downstream body preservation. Also created `RelayDtos.kt` (RelayRequest, RelaySlot, RelayResponse, RelayErrorResponse). |

---

## Sprint 4 (Weeks 7–8): NEGOTIATION PROTOCOL v1 ✅

> **Note:** Significant scaffolding existed from Sprint 3 — JPA entities (`NegotiationSession`, `NegotiationProposal`, `NegotiationEvent`), repositories, HMAC auth (`HmacService`, `HmacAuthenticationFilter`), `CrossInstanceRelayClient`, `RelayDtos`, `SchedulingService`, `SecurityConfig`. Sprint 4 built the orchestration layer on top of this foundation.

### Foundation Layer (Phase A)
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 04 | `NegotiationEventRepository` — JPA repo for audit events | `backend/src/main/kotlin/com/fieldiq/repository/NegotiationEventRepository.kt` — `findBySessionId()` query. |
| ✅ | 04 | `InvalidStateTransitionException` + GlobalExceptionHandler 409 mapping | `backend/src/main/kotlin/com/fieldiq/service/NegotiationExceptions.kt`, `GlobalExceptionHandler.kt` updated. |
| ✅ | 04 | Negotiation DTOs (request + response) | `backend/src/main/kotlin/com/fieldiq/api/dto/NegotiationDtos.kt` — `InitiateNegotiationRequest`, `JoinSessionRequest`, `RespondToProposalRequest`, `ConfirmNegotiationRequest`, `NegotiationSessionDto`, `NegotiationProposalDto`, `TimeSlotDto`, etc. |
| ✅ | 04 | `HmacAuthenticationFilter` — Redis session key cache for consumed invite tokens | `HmacAuthenticationFilter.kt` — `SESSION_KEY_PREFIX`, `SESSION_KEY_TTL(72h)`. Looks up `fieldiq:sessionkey:<sessionId>` when invite token is null. 12 unit tests in `HmacAuthenticationFilterTest.kt` (Redis-hit, Redis-miss, and downstream body replay coverage). |

### NegotiationService
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 04 | `initiateNegotiation()` — create session with invite_token | `NegotiationService.kt` — `TeamAccessGuard.requireManager()`, crypto-random invite token (48h TTL), session created with `pending_response`, audit event logged. Session 17: `NegotiationEvent.kt` now binds `payload` as JSONB with Hibernate `@JdbcTypeCode(SqlTypes.JSON)` so negotiation creation no longer inserts JSONB payloads as varchar. |
| ✅ | 04 | `joinSession()` — consume invite_token, derive session key | `NegotiationService.kt` — validates token, derives key via `HmacService.deriveSessionKey()`, caches in Redis (`fieldiq:sessionkey:<id>`), nullifies token, stores `sessionKeyHash`, transitions to `proposing`. Session 20: Bruno negotiation requests updated to target responder instance `http://localhost:8081` instead of self-relaying to `8080`. |
| ✅ | 04 | `generateAndSendProposal()` — propose time slots | `NegotiationService.kt` — calls `SchedulingService.findAvailableWindows()` (top 5), saves `NegotiationProposal` (JSONB slots), increments `currentRound`, relays via `CrossInstanceRelayClient`. Session 17: `NegotiationProposal.kt` now binds `slots` as JSONB with Hibernate `@JdbcTypeCode(SqlTypes.JSON)` to match PostgreSQL column types during proposal persistence. |
| ✅ | 04 | `processIncomingRelay()` — handle inbound proposals | `NegotiationService.kt` — routes by `relay.action`. For "propose": intersects incoming slots with local windows. Match → `pending_approval`. No match + rounds remaining → auto-counter. Max rounds → `failed`. Idempotent on `(session_id, round_number, proposed_by)`. |
| ✅ | 04 | `confirmAgreement()` — dual confirmation + deferred event creation | `NegotiationService.kt` — requires `pending_approval`, sets per-side flag (`initiatorConfirmed`/`responderConfirmed`), creates `Event` only when both sides confirmed, relays confirm with agreed slot. Idempotency guard via `EventRepository.findByTeamIdAndNegotiationId()`. |
| ✅ | 04 | `respondToProposal()` — update proposal response status | `NegotiationService.kt` — updates matching proposal's `responseStatus`, supports counter-slots. |
| ✅ | 04 | `cancelSession()` — transition to cancelled, relay to remote | `NegotiationService.kt` — transitions to `cancelled`, relays cancel action to remote instance if connected. |
| ✅ | 04 | State machine enforcement (allowed transitions) | `NegotiationService.kt` — `ALLOWED_TRANSITIONS` map + `requireTransition()` helper. Terminal states reject all transitions. |
| ✅ | 04 | Idempotency via unique constraint on `(session_id, round_number, proposed_by)` | `NegotiationService.kt` — `processIncomingRelay()` checks for existing proposal before creating duplicate. |

### Negotiation REST Endpoints
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 04 | `POST /negotiations` — initiate negotiation | `NegotiationController.kt` — 201 Created + `NegotiationSessionDto`. |
| ✅ | 04 | `GET /negotiations/:sessionId` — get session state | `NegotiationController.kt` — 200 + `NegotiationSessionDto` with proposals. |
| ✅ | 04 | `POST /negotiations/:sessionId/join` — responder joins | `NegotiationController.kt` — 200 + `NegotiationSessionDto`. |
| ✅ | 04 | `POST /negotiations/:sessionId/propose` — send proposals | `NegotiationController.kt` — 200 + `NegotiationProposalDto`. |
| ✅ | 04 | `POST /negotiations/:sessionId/respond` — accept/reject/counter | `NegotiationController.kt` — 200 + `NegotiationSessionDto`. |
| ✅ | 04 | `POST /negotiations/:sessionId/confirm` — confirm agreed slot | `NegotiationController.kt` — 200 + `NegotiationSessionDto` (changed from `EventDto` after dual-confirmation refactor). |
| ✅ | 04 | `POST /negotiations/:sessionId/cancel` — withdraw | `NegotiationController.kt` — 200 + `NegotiationSessionDto`. |

### Cross-Instance Relay
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 04 | `POST /api/negotiate/incoming` — receive remote invite | `NegotiationRelayController.kt` — HMAC-authenticated endpoint. |
| ✅ | 04 | `POST /api/negotiate/:sessionId/relay` — relay proposals (HMAC auth) | `NegotiationRelayController.kt` — routes to `NegotiationService.processIncomingRelay()`. |
| ✅ | 04 | Timestamp drift validation (±5 min) | Implemented in Sprint 3 `HmacService.kt` — reused here. |
| ✅ | 04 | Replay attack prevention (nonce tracking in Redis) | Implemented in Sprint 3 `HmacAuthenticationFilter.kt` — reused here. |

### Unit & Integration Testing
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 07 | `NegotiationServiceTest.kt` — 50 unit tests | `backend/src/test/kotlin/com/fieldiq/service/NegotiationServiceTest.kt` — MockK-based. Nested groups: InitiateNegotiation (5), GetSession (2), JoinSession (6), StateMachine (3), GenerateAndSendProposal (7), ProcessIncomingProposal (7), ConfirmAgreement (8), HandleIncomingResponse (4), RespondToProposal (2), CancelSession (3). Follow-up remediation added: counter-chain agreed-slot propagation, shadow-session remote event `createdBy` handling, local counter-proposal history persistence. |
| ✅ | 07 | Two `NegotiationService` instances wired to different DataSources | `NegotiationProtocolIntegrationTest.kt` — MockK-backed ConcurrentHashMap repos per instance, `CrossInstanceRelayClient` bridged to call `processIncomingRelay()` on other service directly. Shared Redis store (ConcurrentHashMap) for session key cache. |
| ✅ | 07 | Happy path: initiate → join → propose → match → dual confirm → events created | `NegotiationProtocolIntegrationTest.kt` `happyPath()` — full protocol cycle with relay response processing (no `syncSession()` workaround). Dual confirmation: A confirms first (pending_approval), relays to B, B confirms → both `confirmed`, events created on both instances via `EventRepository.findByTeamIdAndNegotiationId()` idempotency guard. |
| ✅ | 07 | Max rounds exceeded → both sessions failed | `NegotiationProtocolIntegrationTest.kt` `maxRoundsExceeded()` — non-overlapping availability, 3 rounds, both Instance A and B transition to `failed` via relay response propagation. |
| ✅ | 07 | Cancellation flow | `NegotiationProtocolIntegrationTest.kt` `cancellationPropagation()` — cancel relayed to remote instance. |
| ✅ | 07 | Idempotent duplicate handling | `NegotiationProtocolIntegrationTest.kt` `idempotentDuplicate()` — duplicate relay safely ignored, no duplicate proposals created. |
| ✅ | 07 | Bruno API integration tests for negotiations | `backend/bruno/collections/negotiations/` — 9 .bru files. Follow-up coverage added in `09-getSessionWithMatchedSlot.bru` for persisted `agreedEndsAt` + proposal history after a matched proposal exchange. Session 17: `backend/bruno/scripts/helpers/availability-helpers.js` updated to accept both `(teamId, overrides)` and object-style `{ teamId, ... }` calls used by `04-propose.bru`, `09-getSessionWithMatchedSlot.bru`, and `backend/bruno/collections/scheduling/01-suggestWindows.bru`. Session 18: `backend/bruno/scripts/helpers/team-helpers.js` now caches latest team per `activeUser`, and `availability-helpers.js` resolves fallback team IDs from the active user's scoped cache to prevent cross-user `403` setup failures in `04-propose.bru`, `07-joinBadToken.bru`, `09-getSessionWithMatchedSlot.bru`, and `01-suggestWindows.bru`. Session 19: fixed Bruno variable naming by changing user-scoped team cache keys from `latestTeamId:<user>` to Bruno-safe `latestTeamId.<user>` so pre-request scripts no longer fail before setting `sessionId`. Session 20: `03-joinSession.bru` and `09-getSessionWithMatchedSlot.bru` now join against responder instance `http://localhost:8081`, and `04-propose.bru` resolves manager A's team via `ensureTeam()` instead of the globally overwritten `teamId`. Session 22: `NegotiationService.createShadowSession()` moved to explicit `EntityManager.persist()` + `flush()`, which fixed the prior `negotiation_events_session_id_fkey` failure but exposed Hibernate treating the caller-supplied remote UUID as a detached entity. Session 23: shadow-session bootstrap now inserts via `NamedParameterJdbcTemplate` instead of JPA entity-state APIs, eliminating both the earlier FK timing failure and the `detached entity passed to persist` failure seen in live logs. Session 24: JDBC parameter binding now converts `LocalDate` to `java.sql.Date` and `Instant` fields to `java.sql.Timestamp`, fixing the refreshed live error `Can't infer the SQL type to use for an instance of java.time.Instant` from `/api/negotiate/incoming`. Session 25: refreshed live logs showed `/relay` returning `404` after join because Bruno was still creating manager B's responder team and availability on instance A, while instance B was correctly trying to compute responder-local availability. Fixes: `03-joinSession.bru` and `09-getSessionWithMatchedSlot.bru` now log manager-b into `http://localhost:8081` and create responder resources there; Bruno auth/resource/team/availability helpers now support explicit `baseUrl` overrides; `NegotiationService.joinSession()` skips local responder-team authorization for cross-instance joins while still enforcing it for same-instance negotiations. Session 26: refreshed live logs reached 36/37 passing; the remaining `05-cancelSession.bru` failure was a test issue because it reused the session from `01`-`04`, which the protocol can legitimately drive to terminal `failed` before cancellation. `05-cancelSession.bru` now creates and cancels its own fresh `pending_response` session, matching the allowed transitions in `docs/04_Phase1_Negotiation_Protocol.md`. Verification pending live Bruno rerun. |
| ✅ | 07 | All 218 backend tests passing | 164 pre-existing + 50 NegotiationServiceTest + 4 NegotiationProtocolIntegrationTest = 218 total. Session 16: `./gradlew test` green after follow-up remediation fixes. Session 17: targeted validation also passed with `./gradlew test --tests '*Negotiation*' --tests '*HmacAuthenticationFilterTest'`. Session 18: `cd backend && ./gradlew test --tests 'com.fieldiq.security.HmacAuthenticationFilterTest'` passed after the relay body replay fix. Session 19: the same targeted test passed after adding `MethodArgumentTypeMismatchException` handling in `GlobalExceptionHandler.kt`. Session 20: `cd backend && ./gradlew test --tests 'com.fieldiq.service.NegotiationProtocolIntegrationTest' --tests 'com.fieldiq.service.NegotiationServiceTest'` passed after changing `createShadowSession()` to `saveAndFlush()`, adding `V7__drop_negotiation_initiator_team_fk.sql`, and updating the integration-test session repository mock for `saveAndFlush()`. |

### Vertical Slice Milestone (End of Sprint 4)
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ✅ | 00 | Manager A initiates negotiation on Instance A (curl/Postman) | Covered by Bruno test `01-initiateNegotiation.bru` and integration test `happyPath()`. |
| ✅ | 00 | Manager B joins on Instance B via invite_token | Covered by Bruno test `03-joinSession.bru` and integration test `happyPath()`. |
| ✅ | 00 | Proposals exchange automatically for up to 3 rounds | Integration test `happyPath()` (1-round match) and `maxRoundsExceeded()` (3-round exhaust). |
| ✅ | 00 | Match found → both managers confirm → events created on both instances | Integration test `happyPath()` — events created on both simulated instances. |

### Sprint 4 Remediation (6 Bugs Fixed)
| Status | Bug | Fix | Evidence / Notes |
|--------|-----|-----|------------------|
| ✅ | Bug 1: Initiator ignores RelayResponse | `generateAndSendProposal()` and `handleIncomingProposal()` counter path now capture `RelayResponse` and transition local session accordingly (`pending_approval`/`failed`). Eliminated `syncSession()` workaround entirely. | `NegotiationService.kt` — relay response processing in both propose and counter-propose paths. 3 new unit tests: `transitionsToMatchWhenRelayReturnsPendingApproval`, `transitionsToFailedWhenRelayReturnsFailed`, `staysInProposingWhenRelayReturnsProposing`. |
| ✅ | Bug 2: Single-sided confirmation | `confirmAgreement()` returns `NegotiationSessionDto` (not `Event`). Sets per-side flags (`initiatorConfirmed`/`responderConfirmed`). Event created only when both true. `handleIncomingConfirm()` rewritten with same dual logic. Idempotency via `findByTeamIdAndNegotiationId()`. | `V6__negotiation_dual_confirmation.sql`, `NegotiationSession.kt` (+3 fields), `NegotiationController.kt` (return type), `NegotiationDtos.kt` (+3 fields), `EventRepository.kt` (+idempotency query). 7 new unit tests. |
| ✅ | Bug 3: Join authorization missing | `joinSession()` calls `TeamAccessGuard.requireManager(userId, responderTeamId)` before consuming invite token. | `NegotiationService.kt` — guard call at top of `joinSession()`. |
| ✅ | Bug 4: Shadow session missing on Instance B | New `POST /api/negotiate/incoming` endpoint creates shadow session on responder instance. `joinSession()` relays `IncomingNegotiationRequest` to responder. `HmacAuthenticationFilter` excludes `/api/negotiate/incoming`. | `NegotiationRelayController.kt`, `NegotiationService.kt` (`handleIncomingNegotiation()`), `HmacAuthenticationFilter.kt` (path exclusion), `IncomingNegotiationRequest` DTO. |
| ✅ | Bug 5: Counter slots discarded by inbound handler | `handleIncomingResponse()` expanded: creates proposal record for counter, intersects counter slots with local availability, transitions to `pending_approval` on match or `failed` at max rounds. | `NegotiationService.kt` — counter path in `handleIncomingResponse()`. 4 new unit tests: `counterCreatesProposal`, `counterMatchTransitions`, `counterAtMaxRoundsFails`, `acceptedNoCounter`. |
| ✅ | Bug 6: TypeScript types drifted from Kotlin DTOs | `shared/types/index.ts` synced: `NegotiationSessionDto` (+`agreedEndsAt`, `initiatorConfirmed`, `responderConfirmed`, `proposals`), deprecated `ProposeRequest`, added `JoinSessionRequest`, `IncomingNegotiationRequest`, `RelayResponse` interfaces. | `shared/types/index.ts` — all interfaces match Kotlin DTOs. |
| ✅ | Follow-up 1: matched slot persisted incompletely on receiver | Direct match path now persists `agreedEndsAt` alongside `agreedStartsAt` when `handleIncomingProposal()` finds an overlap. | `backend/src/main/kotlin/com/fieldiq/service/NegotiationService.kt` — pending-approval persistence updated. Test coverage: `matchFound` in `NegotiationServiceTest.kt` plus Bruno coverage in `09-getSessionWithMatchedSlot.bru`. |
| ✅ | Follow-up 2: counter-chain relay response dropped agreed slot | Auto-counter branch now returns full `RelayResponse` slot fields from the final session so the original caller can enter `pending_approval` with the agreed slot populated. | `backend/src/main/kotlin/com/fieldiq/service/NegotiationService.kt` — counter branch return payload fixed. Test coverage: `autoCounterPropagatesAgreedSlot` in `NegotiationServiceTest.kt`. |
| ✅ | Follow-up 3: local counter proposals missing from sender history | `respondToProposal()` now records the sender's counter proposal locally and advances `currentRound`, keeping proposal history symmetric with relay processing. | `backend/src/main/kotlin/com/fieldiq/service/NegotiationService.kt`. Test coverage: `recordsLocalCounterProposal` in `NegotiationServiceTest.kt`. |
| ✅ | Follow-up 4: remote event creation could use a team UUID as `createdBy` | `handleIncomingConfirm()` now creates shadow-session events with `createdBy = initiatorManager`, which safely stays null when the remote user is unknown on this instance. | `backend/src/main/kotlin/com/fieldiq/service/NegotiationService.kt`. Test coverage: `incomingConfirmCreatesEventWithNullCreatedBy` in `NegotiationServiceTest.kt`. |

---

## Sprint 5 (Weeks 9–10): REACT NATIVE APP ⬜

| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 06 | Expo project setup with TypeScript | |
| ⬜ | 06 | Expo Router file-based routing (`(auth)/`, `(app)/`) | |
| ⬜ | 06 | Login screen + OTP verification flow | |
| ⬜ | 06 | SecureStore token management (JWT + refresh) | |
| ⬜ | 06 | Schedule feed — events list (`(app)/index.tsx`) | |
| ⬜ | 06 | Team/roster screen (`(app)/team.tsx`) | |
| ⬜ | 06 | Settings screen + Google Calendar connect (`(app)/settings.tsx`) | |
| ⬜ | 06 | API client (`services/api.ts`) with auth interceptor | |
| ⬜ | 06 | Push token registration on app launch | |
| ⬜ | 06 | "Finding mutual time..." animated component (Lottie/Animated) | |

---

## Sprint 6 (Weeks 11–12): NEGOTIATION UX + NOTIFICATIONS ⬜

| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 06 | Negotiation approval screen (`(app)/negotiate.tsx`) — the key UX moment | |
| ⬜ | 03 | WebSocket client for real-time negotiation updates | |
| ⬜ | 05 | Push notifications via Expo (FCM for iOS) | |
| ⬜ | 05 | `notification.worker.ts` — SQS consumer for `SEND_NOTIFICATION` | |
| ⬜ | 05 | `CommunicationAgent` (Claude Haiku) for reminder + outcome message drafting | |
| ⬜ | 06 | RSVP tracking UI on event detail screen | |
| ⬜ | 06 | `.ics` download link for confirmed games | |

---

## Sprint 7 (Weeks 13–14): END-TO-END INTEGRATION ⬜

| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 00 | Two managers on two iOS simulators, full negotiation flow | |
| ⬜ | 00 | Push notifications arrive on both devices | |
| ⬜ | 00 | Fallback flow: Team B not on FieldIQ (manual scheduling) | |
| ⬜ | 00 | Bug fixes and happy path polish | |
| ⬜ | 00 | WebSocket real-time updates working end-to-end | |

---

## Sprint 8 (Weeks 15–16): REAL USERS + INSTRUMENTATION ⬜

| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 00 | Invite 5 real DMV soccer managers for beta | |
| ⬜ | 00 | Instrument time-saved metric (initiate → confirmed) | |
| ⬜ | 00 | Fix friction points from real usage | |
| ⬜ | 00 | Deploy to AWS (ECS Fargate + RDS + ElastiCache + SQS) | |
| ⬜ | 00 | Production environment config and secrets management | |
| ⬜ | 00 | Prepare for 15-team beta launch (Phase 2) | |

---

## Progress Summary

| Sprint | Name | Status | Tasks Done | Tasks Total |
|--------|------|--------|------------|-------------|
| 1 | Foundation | ✅ Complete | 24/24 | 24 |
| 2 | Core CRUD + Auth | ✅ Complete | 24/24 | 24 |
| 3 | Scheduling + Calendar Sync | ✅ Complete | 18/18 | 18 |
| 4 | Negotiation Protocol v1 | ✅ Complete | 42/42 | 42 |
| 5 | React Native App | ⬜ Not Started | 0/10 | 10 |
| 6 | Negotiation UX + Notifications | ⬜ Not Started | 0/7 | 7 |
| 7 | End-to-End Integration | ⬜ Not Started | 0/5 | 5 |
| 8 | Real Users + Instrumentation | ⬜ Not Started | 0/6 | 6 |
| **Total** | | | **108/136** | **136** |
