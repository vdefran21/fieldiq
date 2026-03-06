# FieldIQ Phase 1 — Implementation Tracking

**Target:** Working cross-team scheduling negotiation demo + iOS MVP
**Timeline:** 16 weeks (8 sprints)
**Last updated:** 2026-03-06 (session 3 — Sprint 2 KDoc complete)

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
| ✅ | 00 | `infra/localstack-init.sh` — 3 SQS queues auto-created | `infra/localstack-init.sh` |
| ✅ | 03 | Backend skeleton — Spring Boot 3.3, Java 21, Kotlin | `backend/build.gradle.kts`, `FieldIQApplication.kt` |
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
| ✅ | 03 | `SecurityConfig` — stateless JWT security scaffold | `backend/src/main/kotlin/com/fieldiq/security/SecurityConfig.kt` |
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
| ✅ | 07 | Unit tests for AuthService | `AuthServiceTest.kt` — 16 tests (OTP request/verify, refresh rotation, logout) |
| ✅ | 07 | Unit tests for TeamService | `TeamServiceTest.kt` — 10 tests (create, get, addMember, getMembers) |
| ✅ | 07 | Unit tests for EventService | `EventServiceTest.kt` — 12 tests (create, list, update, RSVP upsert, responses) |
| ✅ | 07 | Unit tests for AvailabilityWindowService | `AvailabilityWindowServiceTest.kt` — 10 tests (create, validate, query, delete) + `JwtServiceTest.kt` (10), `OtpRateLimitServiceTest.kt` (11). Total: 87/87 passing |
| ✅ | 07 | Comprehensive KDoc on all 6 test files | All test classes, nested classes, test methods, and properties fully documented per CLAUDE.md standards. `@see` cross-references, security rationale, edge case explanations. 87/87 tests still passing after KDoc. |

---

## Sprint 3 (Weeks 5–6): SCHEDULING + CALENDAR SYNC ⬜

### Google Calendar Integration
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 02 | `GET /auth/google/authorize` — initiate OAuth flow | |
| ⬜ | 02 | `GET /auth/google/callback` — handle OAuth callback | |
| ⬜ | 02 | Token encryption (AES-256-GCM via `TokenEncryptionConverter`) | |
| ⬜ | 02 | Refresh token management for Google tokens | |

### Calendar Sync Agent Worker
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 05 | Agent layer project setup (`agent/package.json`, `tsconfig.json`) | |
| ⬜ | 05 | `calendar-sync.worker.ts` — SQS consumer for `SYNC_CALENDAR` | |
| ⬜ | 05 | Google FreeBusy API integration (read-only) | |
| ⬜ | 05 | Convert FreeBusy → `availability_windows` (source='google_cal') | |

### Scheduling Service
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 03 | `SchedulingService.kt` — deterministic window computation | |
| ⬜ | 03 | `findAvailableWindows()` — find team availability windows | |
| ⬜ | 03 | Window ranking by confidence (% members available) | |
| ⬜ | 03 | `intersectWindows()` — cross-team window matching | |
| ⬜ | 03 | `POST /teams/:teamId/suggest-windows` endpoint | |

### Cross-Instance Relay Scaffolding
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 04 | `CrossInstanceRelayClient` — WebFlux HTTP client | |
| ⬜ | 04 | HMAC-SHA256 signature generation | |
| ⬜ | 04 | HMAC signature validation filter | |

---

## Sprint 4 (Weeks 7–8): NEGOTIATION PROTOCOL v1 ⬜

### NegotiationService
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 04 | `initiateNegotiation()` — create session with invite_token | |
| ⬜ | 04 | `joinSession()` — consume invite_token, derive session key | |
| ⬜ | 04 | `generateAndSendProposal()` — propose time slots | |
| ⬜ | 04 | `processIncomingRelay()` — handle inbound proposals | |
| ⬜ | 04 | `confirmAgreement()` — create events on both teams | |
| ⬜ | 04 | State machine enforcement (allowed transitions) | |
| ⬜ | 04 | Idempotency via unique constraint on `(session_id, round_number, proposed_by)` | |

### Negotiation REST Endpoints
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 04 | `POST /negotiations` — initiate negotiation | |
| ⬜ | 04 | `GET /negotiations/:sessionId` — get session state | |
| ⬜ | 04 | `POST /negotiations/:sessionId/join` — responder joins | |
| ⬜ | 04 | `POST /negotiations/:sessionId/propose` — send proposals | |
| ⬜ | 04 | `POST /negotiations/:sessionId/respond` — accept/reject/counter | |
| ⬜ | 04 | `POST /negotiations/:sessionId/confirm` — confirm agreed slot | |
| ⬜ | 04 | `POST /negotiations/:sessionId/cancel` — withdraw | |

### Cross-Instance Relay
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 04 | `POST /api/negotiate/incoming` — receive remote invite | |
| ⬜ | 04 | `POST /api/negotiate/:sessionId/relay` — relay proposals (HMAC auth) | |
| ⬜ | 04 | Timestamp drift validation (±5 min) | |
| ⬜ | 04 | Replay attack prevention (nonce tracking in Redis) | |

### Integration Testing
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 07 | Two `NegotiationService` instances wired to different DataSources | |
| ⬜ | 07 | Happy path: initiate → join → propose → match → confirm → events created | |
| ⬜ | 07 | Max rounds exceeded → session failed | |
| ⬜ | 07 | Cancellation flow | |

### Vertical Slice Milestone (End of Sprint 4)
| Status | Doc | Task | Evidence / Notes |
|--------|-----|------|------------------|
| ⬜ | 00 | Manager A initiates negotiation on Instance A (curl/Postman) | |
| ⬜ | 00 | Manager B joins on Instance B via invite_token | |
| ⬜ | 00 | Proposals exchange automatically for up to 3 rounds | |
| ⬜ | 00 | Match found → both managers confirm → events created on both instances | |

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
| 1 | Foundation | ✅ Complete | 23/23 | 23 |
| 2 | Core CRUD + Auth | ✅ Complete | 21/21 | 21 |
| 3 | Scheduling + Calendar Sync | ⬜ Not Started | 0/14 | 14 |
| 4 | Negotiation Protocol v1 | ⬜ Not Started | 0/18 | 18 |
| 5 | React Native App | ⬜ Not Started | 0/10 | 10 |
| 6 | Negotiation UX + Notifications | ⬜ Not Started | 0/7 | 7 |
| 7 | End-to-End Integration | ⬜ Not Started | 0/5 | 5 |
| 8 | Real Users + Instrumentation | ⬜ Not Started | 0/6 | 6 |
| **Total** | | | **44/104** | **104** |
