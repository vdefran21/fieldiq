# FieldIQ — Next Steps Evaluation

**Date:** 2026-03-06
**Based on:** `docs/IMPLEMENTATION_TRACKING.md`, Phase 1 docs `00–07`, and current repository state

---

## Conclusion

The critical path is clear:

1. Finish the remaining Sprint 3 infrastructure work.
2. Build Sprint 4 negotiation end to end.
3. Only then start the mobile app work in Sprint 5.

The docs and tracker agree that Sprint 1 and Sprint 2 are complete, SchedulingService is already implemented, and the main missing work is concentrated in four areas:

- Google Calendar OAuth + token handling
- Agent layer setup + calendar sync worker
- Cross-instance relay auth and transport
- Negotiation service, endpoints, and tests

There is already useful negotiation groundwork in place. The schema exists, the backend has negotiation entities and repositories, and shared TypeScript DTOs already describe the API contract. What is still missing is the executable behavior around those models.

---

## What Exists Already

The following foundation appears to be in place:

- Negotiation tables in `V2__negotiation_schema.sql`
- `NegotiationSession`, `NegotiationProposal`, and `NegotiationEvent` entities
- Negotiation repositories
- Shared negotiation DTOs and WebSocket message types in `shared/types/index.ts`
- `SchedulingService` and `POST /teams/:teamId/suggest-windows`

This matters because Sprint 4 is not a greenfield build. The data model and contracts already exist, so the next implementation work should focus on orchestration and transport instead of revisiting schema design.

---

## Immediate Next Steps

### 1. Finish Sprint 3 Google Calendar integration

This is still open in the tracker and is defined clearly in the Auth & Calendar doc. The backend work should include:

- `GET /auth/google/authorize`
- `GET /auth/google/callback`
- encrypted token persistence using AES-256-GCM
- refresh token handling for future syncs
- enqueueing an immediate `SYNC_CALENDAR` task on successful connect

Why this comes first: the negotiation flow is supposed to use synced busy blocks, and the design assumes Google Calendar availability is part of the scheduling signal.

### 2. Create the agent project and implement calendar sync

The workspace currently has no `agent/` project, even though the docs assume one. The first version should be minimal:

- `agent/package.json`
- `agent/tsconfig.json`
- SQS worker bootstrap
- `calendar-sync.worker.ts`
- Google FreeBusy integration
- transformation of busy blocks into `availability_windows` with `source='google_cal'`

Why this is next: once Google OAuth is working, this becomes the first consumer of the stored calendar credentials and proves the async architecture.

### 3. Build the cross-instance relay foundation

This is the highest-leverage backend task because Sprint 4 depends on it completely. The work should include:

- `CrossInstanceRelayClient`
- HMAC-SHA256 signature generation
- timestamp drift validation (±5 minutes)
- replay prevention via Redis nonce storage
- HMAC validation on relay endpoints

Why this should not be delayed: the docs define the entire negotiation protocol in terms of HMAC-authenticated relay requests. Without this layer, `NegotiationService` cannot be implemented correctly.

---

## After Sprint 3

### 4. Implement `NegotiationService`

Once relay auth exists, implement the workflow methods already specified in the backend docs:

- `initiateNegotiation()`
- `joinSession()`
- `generateAndSendProposal()`
- `processIncomingRelay()`
- `confirmAgreement()`
- state transition enforcement
- idempotency handling using the existing unique constraint

This is the core product logic and the main source of technical risk in the roadmap.

### 5. Add the negotiation REST endpoints

After the service exists, expose the public API:

- `POST /negotiations`
- `GET /negotiations/:sessionId`
- `POST /negotiations/:sessionId/join`
- `POST /negotiations/:sessionId/propose`
- `POST /negotiations/:sessionId/respond`
- `POST /negotiations/:sessionId/confirm`
- `POST /negotiations/:sessionId/cancel`

The shared types already anticipate these endpoints, so the Kotlin DTOs should align to that contract.

### 6. Add integration testing before mobile work

The testing doc is explicit that negotiation must be validated with two service instances wired to different data sources in-process. The minimum set should cover:

- happy path: initiate → join → propose → match → confirm
- max rounds exceeded
- cancellation flow
- duplicate proposal / idempotency
- invalid HMAC signature rejection
- invalid state transition rejection

This is the gate for the Sprint 4 vertical slice.

---

## Recommended Implementation Order

If the goal is the shortest path to the Phase 1 milestone, the next work should be sequenced like this:

1. Google OAuth backend flow and token encryption
2. Agent scaffolding and `calendar-sync.worker.ts`
3. Cross-instance relay auth and relay endpoints
4. `NegotiationService` and negotiation endpoints
5. Negotiation integration tests and Bruno coverage
6. Expo mobile scaffold only after the backend vertical slice works

---

## Final Recommendation

Do not start Sprint 5 mobile work yet.

The best next implementation target is to close out the unfinished Sprint 3 platform work, then move directly into Sprint 4 negotiation. The negotiation schema and API contracts already exist, so the main gap is behavior, not design. That means the most efficient path forward is to finish the infrastructure that the negotiation protocol depends on, then implement and test the protocol end to end before investing in UI.