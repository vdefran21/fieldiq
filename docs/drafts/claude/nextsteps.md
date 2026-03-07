# FieldIQ — Next Steps Evaluation

**Date:** 2026-03-06
**Based on:** `docs/IMPLEMENTATION_TRACKING.md` + all Phase 1 design docs (00–07)

---

## Current Status

**Sprints 1–2 are complete** (47/47 tasks). **Sprint 3 is in progress** at 7/16 tasks — the SchedulingService is done, but the remaining 9 tasks are all not started.

---

## Immediate Next Steps (Sprint 3 — finish it)

Sprint 3 has three unfinished work streams, in priority order:

### 1. Cross-Instance Relay Scaffolding (Doc 04) — do this first
This is the foundation for Sprint 4's negotiation protocol and has no external dependencies:
- `CrossInstanceRelayClient` — WebFlux HTTP client for instance-to-instance calls
- HMAC-SHA256 signature generation (`sessionKey = HMAC-SHA256(instanceSecret, inviteToken)`, sign `sessionId + timestamp + body`)
- HMAC signature validation filter (timestamp drift ±5min, replay prevention via Redis nonce)

### 2. Google Calendar OAuth Endpoints (Doc 02)
Backend endpoints to wire up the Google OAuth flow:
- `GET /auth/google/authorize` — redirect to Google consent screen
- `GET /auth/google/callback` — exchange auth code for tokens
- `TokenEncryptionConverter` — AES-256-GCM encryption for stored tokens
- Google refresh token management

### 3. Calendar Sync Agent Worker (Doc 05)
The agent layer's first worker — depends on Google Calendar endpoints being done:
- Agent project setup (`agent/package.json`, `tsconfig.json`)
- `calendar-sync.worker.ts` — SQS consumer for `SYNC_CALENDAR`
- Google FreeBusy API integration (read-only)
- Convert FreeBusy busy blocks → `availability_windows` (source=`google_cal`)

---

## After Sprint 3 — Sprint 4: Negotiation Protocol v1

This is the **core IP** — 18 tasks covering:
- **NegotiationService** (7 tasks) — state machine, initiate/join/propose/confirm flows, idempotency
- **REST Endpoints** (7 tasks) — full CRUD for negotiation lifecycle
- **Cross-Instance Relay** (4 tasks) — incoming relay endpoint, HMAC-authenticated proposal relay
- **Integration Tests** — dual-DataSource NegotiationService test, happy path, max rounds, cancellation
- **Vertical Slice Milestone** — two instances negotiating end-to-end via curl

---

## Recommendation

**Start with the Cross-Instance Relay Scaffolding** — it's the Sprint 3 task with the highest downstream impact. Sprint 4's entire negotiation protocol depends on it, and it has zero external dependencies (unlike Google Calendar which needs API credentials). The HMAC client/filter plus the relay contract from `docs/04_Phase1_Negotiation_Protocol.md` are fully specified and ready to implement.

Google Calendar integration can be deferred or parallelized since it's a separate concern — the scheduling service already works with manually-entered availability windows.
