# Next Steps Toward End-to-End Validation

## Summary

The highest-value next step is no longer exposing negotiation from mobile; that path now exists. The next job is to validate the full manager workflow across two FieldIQ instances, prove push delivery on real devices, and close the remaining beta-hardening gaps.

The recommended priority order is:
1. Run the complete Sprint 7 negotiation flow end to end.
2. Validate Expo push delivery on two physical iOS devices.
3. Close the remaining pre-beta hardening and observability work.
4. Defer convenience navigation and secondary UX until the core loop is proven.

## Key Changes

### 1. Validate the complete manager loop
- Treat the existing mobile schedule entrypoints as the primary path:
  - `Create event`
  - `Start negotiation`
  - `Join negotiation`
- Verify the full north-star flow from fresh login to visible confirmed event:
  - create first team
  - set baseline availability
  - initiate negotiation on instance A
  - join on instance B
  - advance proposals to `pending_approval`
  - confirm on both sides
  - see resulting event and `.ics` link
- Use this phase to fix validation bugs and UX friction, not to redesign the flow from scratch.

### 2. Prove delivery on real devices
- Push transport is now implemented in the agent worker; the remaining gap is physical-device validation.
- Treat Sprint 7 acceptance as two physical iOS devices, not simulators.
- Capture evidence for both success and failure cases:
  - negotiation update push
  - confirmation push
  - missing local Expo config remains non-fatal in development

### 3. Finish the pre-beta hardening work
- Google Calendar:
  - narrow scope from broad read-only access to the smallest viable free/busy scope
  - document browser-based native OAuth / AuthSession + PKCE expectations
- WebSocket:
  - keep the short-lived `wsToken` exchange model
  - add handshake URL/query redaction in logs
  - close websocket sessions on logout or token expiry
- Security / Ops:
  - finish the minimum DevSecOps lane: dependency review, secret scanning / push protection, Dependabot, and SAST coverage
  - finish observability: health/readiness, structured logs, correlation IDs, queue and negotiation metrics
- Policy:
  - decide whether `player_name` stays in scope for beta
  - decide whether Postgres RLS is required before shared-production deployment

### 4. Defer secondary product work
- Do not prioritize season switching next.
- Do not prioritize RSVP detail screens or CommunicationAgent polish unless they directly unblock Sprint 7 validation.
- Keep the current priority order aligned with the review addendum’s P0 / P1 / P2 framing.

## Important API / Interface Changes

- No new Phase 1 negotiation API surface should be necessary unless validation reveals a real blocker.
- The current mobile and backend flow should be validated against these existing contracts:
  - `POST /teams/:teamId/events`
  - `POST /negotiations`
  - `POST /negotiations/:sessionId/join`
  - `POST /negotiations/:sessionId/socket-token`
  - existing `GET /negotiations/:sessionId`
- Avoid new schema work unless an end-to-end validation gap proves the current contract is insufficient.

## Test Plan

- Fresh-user validation:
  - log in
  - create first team
  - set team availability
  - start negotiation from the app
- Cross-instance flow:
  - Team A initiates on instance A
  - Team B joins from instance B
  - status updates render in the negotiation screen
  - proposals advance to `pending_approval`
  - both confirmations create events and expose `.ics`
- Device validation:
  - both managers receive push notifications on physical devices
  - confirmation state stays visible even if the websocket misses an update
- Validation gates:
  - `cd mobile && npm run lint`
  - `cd mobile && npx tsc --noEmit`
  - `cd backend && ./gradlew test`
  - `cd backend/bruno && npm test`
  - `cd agent && npm test`
  - `cd agent && npm run build`

## Assumptions

- The mobile schedule entrypoints and negotiation UX are already implemented.
- Push transport exists; the remaining work is proving delivery and hardening operational behavior.
- Beta readiness now depends more on end-to-end validation and hardening than on adding new screens.
