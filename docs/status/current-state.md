# Current State

This summary is a factual synthesis of `docs/status/implementation-tracking.md`, `docs/status/next-steps.md`, and `docs/status/reviews/implementation-review-addendum.md`.

## What Is Implemented Now

- Sprints 1 through 5 are marked complete in the implementation tracker, covering the monorepo foundation, backend CRUD/auth, scheduling, calendar sync, negotiation protocol, and the initial React Native app.
- The mobile app now includes first-team onboarding, schedule entrypoints for create event / start negotiation / join negotiation, availability setup, and a negotiation screen with live refresh, confirmation, cancellation, and `.ics` lookup.
- The backend has cross-instance negotiation, HMAC relay security, Google Calendar read-only integration, and observability basics such as actuator exposure and correlation IDs.
- The agent layer has implemented calendar sync and notification workers, and the notification worker now calls Expo's push API instead of logging-only delivery attempts.

## What Is In Progress

- Sprint 6 remains in progress because physical-device validation for Expo push delivery is still pending, while `CommunicationAgent` and RSVP event-detail UI are still not started.
- Sprint 8 remains in progress because DevSecOps guardrails and observability are underway but not complete.
- Sprint 7 end-to-end integration work is still marked not started in the tracker.

## Highest-Priority Remaining Work

- The status docs continue to prioritize the manager-facing scheduling loop: make sure the path from team creation into negotiation is fully validated end to end, including join flow, confirmation, and visible resulting events.
- Complete real push validation on physical devices rather than stopping at local or simulator-level verification.
- Finish the beta-hardening work called out in the review addendum: WebSocket auth hardening, tighter OAuth/security posture, DevSecOps automation, and production-grade observability.

## Current Beta Blockers

- External-beta readiness is still blocked by incomplete push validation, unfinished end-to-end integration, and incomplete hardening/observability work.
- The review addendum also calls out unresolved pre-beta decisions around minors-data handling and whether shared-production deployment requires Postgres row-level security.

## Recommended Immediate Focus

- Run the Sprint 7 end-to-end negotiation flow across two instances and verify the real manager loop, including status propagation and `.ics` visibility.
- Validate Expo push delivery on physical devices.
- Close the remaining P0 hardening work from the implementation review addendum before treating the product as beta-ready.
