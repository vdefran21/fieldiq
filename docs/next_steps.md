# Next Steps After First Team Creation

## Summary

The highest-value next step is to close the product loop from "manager logs in and creates a team" to "manager can actually initiate scheduling work." Right now the app stops at an empty schedule, which is consistent with the current implementation and docs, but it means Phase 1 still lacks the user-visible path into negotiation.

The recommended priority order is:
1. Add a mobile entrypoint to create or surface a schedulable event/negotiation.
2. Finish the negotiation UX around that flow.
3. Only then add convenience navigation like season/team switching.

## Key Changes

### 1. Add a real action from the empty schedule state
- Replace the passive "No events scheduled yet" state with manager actions:
  - `Create event`
  - `Start negotiation`
- Keep this lightweight. Phase 1 does not need full calendar management yet; it needs a way to reach the negotiation flow from the mobile app.
- Use the existing backend contracts where possible:
  - event creation via `POST /teams/:teamId/events`
  - negotiation initiation via `POST /negotiations`
- If negotiation initiation needs more data than the current UI can supply, add a simple two-step flow:
  - choose initiating team
  - enter date range + duration + preferred days

### 2. Make negotiation reachable and testable from mobile
- After initiation, route directly into `/(app)/negotiate/:id`.
- Show the invite token or shareable join details somewhere in the flow so Team B can join from the second instance.
- Keep the current hidden negotiation route, but ensure the user can reach it without needing a pre-existing event carrying `negotiationId`.
- Bring the screen closer to the spec in [docs/06_Phase1_Mobile.md](/Users/thedaego/fieldiq/docs/06_Phase1_Mobile.md):
  - better pending-approval presentation
  - explicit "Suggest different time" or equivalent counter path if supported by current backend API
  - optional animated "Finding mutual time..." state if time allows

### 3. Finish the missing Sprint 6 delivery pieces that affect real validation
- Push:
  - keep local-dev behavior non-fatal
  - next real step is wiring actual Expo push transport in the agent worker instead of logging only
- Settings:
  - leave calendar connect as placeholder unless backend OAuth handoff is ready
- Negotiation confirmation:
  - verify confirmed sessions reliably surface the `.ics` link from the created event
- Event/RSVP detail:
  - only build if needed to support Sprint 7’s north-star flow

### 4. Defer season navigation until after the core loop works
- Do not prioritize season switching next.
- The docs and current architecture are centered on one-team MVP validation, and the app still lacks the more important "create scheduling work" path.
- Add season/team selection after the mobile app can:
  - create a team
  - create/initiate scheduling work
  - complete a negotiation
  - show the resulting event

## Important API / Interface Changes

- Mobile likely needs to start using these existing backend APIs as first-class UI flows:
  - `POST /teams/:teamId/events`
  - `POST /negotiations`
  - `POST /negotiations/:sessionId/join`
  - existing `GET /negotiations/:sessionId`
- If not already present in the mobile API client, add initiation/join helpers and any request DTOs required by the shared types.
- No new backend schema should be introduced unless a real gap appears during flow design.

## Test Plan

- Fresh user:
  - log in
  - create first team
  - see action to create event or start negotiation from empty schedule
- Negotiation initiation:
  - create a negotiation from mobile
  - navigate into the negotiation screen immediately
  - verify status updates render as the backend state changes
- Cross-instance flow:
  - Team A initiates on mobile
  - Team B joins on the other instance
  - proposals advance to `pending_approval`
  - confirmation creates events and exposes `.ics`
- Validation gates:
  - `cd mobile && npm run lint`
  - `cd mobile && npx tsc --noEmit`
  - `cd backend && ./gradlew test`
  - `cd agent && npm test`
  - rerun Bruno negotiation collection once the mobile-triggered negotiation path is stable

## Assumptions

- Default priority is the core manager workflow, not season/team navigation polish.
- Existing backend negotiation endpoints are sufficient for the next mobile step.
- Calendar connect remains out of the critical path until the mobile app can complete the north-star scheduling loop.
