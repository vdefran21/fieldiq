Implement a tightly scoped Phase 1 addition for FieldIQ with two goals:

1) Add deterministic seeded demo availability for local two-instance development.
2) Add a minimal manager-facing availability setup screen in the Expo mobile app.

Constraints:
- Reuse the existing availability model and endpoints.
- Do not redesign negotiation flow.
- Do not introduce a new schema unless absolutely necessary.
- Prefer recurring weekly manual availability windows using day_of_week.
- Keep Google Calendar as optional conflict subtraction only.

Backend context:
- availability_windows already exists and supports available/unavailable plus manual/google_cal sources.
- POST /users/me/availability already exists.
- GET /teams/:teamId/availability already exists.
- SchedulingService already consumes availability + calendar busy + events.
- Two-instance local dev already exists with separate instance IDs and DBs.

Required work:

A. Demo seeding
- Add a dev/demo-only seed path that creates deterministic recurring availability for instance A and instance B.
- Seed believable windows that guarantee at least one mutual overlap for negotiation demos.
- Make seeding instance-aware using FIELDIQ_INSTANCE_ID or similar existing config.
- Add a reset/reseed workflow or scripts.
- Add tests or verification that seeded availability produces at least one mutual schedulable overlap.

B. Mobile availability flow
- Add a new authenticated screen at mobile/app/(app)/availability.tsx.
- Add navigation to it from the schedule empty state and/or team screen.
- Add API client helpers in mobile/services/api.ts for getTeamAvailability and createAvailabilityWindow.
- Build a minimal UI to create recurring weekly availability windows with:
  - day of week
  - start time
  - end time
  - default windowType=available
- Show a simple list of existing windows.
- Add an empty-state shortcut: “Use suggested availability” to create a small default set of windows.
- Update the schedule empty state so that if the manager has a team but no availability, the app surfaces “Set availability” prominently.

Acceptance criteria:
- Local instance A and B can be seeded with different recurring windows.
- A seeded negotiation path reliably finds at least one overlap.
- A manager can add recurring weekly availability in mobile.
- Saved windows persist through the existing backend API.
- Mobile lint and typecheck pass.
- Backend tests remain green with new availability/demo seed coverage added.

Please inspect the existing codebase first, identify current availability DTO/service shapes, and implement the smallest coherent change set that satisfies the above without broad refactors.