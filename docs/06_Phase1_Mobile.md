# FieldIQ -- Phase 1 Mobile (React Native Expo)

iOS first. Keep it minimal -- the AI does the work, the UI just shows results and asks for approval.

---

## Screen Inventory -- Phase 1 Only

```
/login                  Phone number entry -> OTP verify -> JWT stored in SecureStore
/(app)/index            Schedule feed: upcoming events, pending negotiations, create-team CTA when account has no teams
/(app)/team             Roster list, member availability status, RSVP tracking, create-team CTA when account has no teams
/(app)/availability     Manager baseline recurring availability setup for the current team
/(app)/create-event     Lightweight event creation from the empty schedule state
/(app)/start-negotiation Minimal negotiation-initiation form that routes into the live session
/(app)/join-negotiation Cross-instance join flow using shared session details
/(app)/negotiate/:id    Negotiation approval screen (the key UX moment)
/(app)/settings         Calendar connect, notification preferences, push token registration
/app/create-team        Lightweight first-team onboarding (name required, sport/age/season optional)
```

First-login behavior matters for Phase 1 validation:
- if the authenticated manager has no teams yet, schedule and roster should not dead-end
- both screens should offer a fast create-team path so the dashboard can populate without separate backend setup
- once a team exists, the schedule screen should also offer quick actions for `Create event`,
  `Start negotiation`, and `Join negotiation`
- if the team has no positive availability windows yet, the schedule screen should
  surface `Set availability` before negotiation so the manager understands why the
  scheduler would otherwise fail immediately
- push registration should be treated as optional in local development when Expo project metadata is not configured

---

## Negotiation Approval Screen -- Most Important UX

```tsx
// This screen is THE killer feature made visible to the user
// When the AI finds a mutual time, this is what the manager sees

export default function NegotiationApprovalScreen() {
  // Subscribes to WebSocket for real-time updates during active negotiation
  // Shows different states:
  //
  // PROPOSING state:
  // ---------------------------------
  //  Negotiating with [Opponent Team]
  //  Round 2 of 3
  //
  //  [Animated "Finding mutual time..." indicator]
  //  Use Lottie animation or Expo built-in Animated API
  //  for a subtle pulsing/searching visual. This is the
  //  moment users feel the magic -- make it delightful.
  //
  //  Finding a time that works for
  //  both teams...
  //  [Send proposal round]
  //  [Suggest different time]
  //  [Cancel Negotiation]
  // ---------------------------------
  //
  // PENDING_APPROVAL state:
  // ---------------------------------
  //  Match Found!
  //  FieldIQ found a time that works
  //  for both teams.
  //
  //  vs. [Opponent Team Name]
  //  Saturday, April 5
  //  10:00 AM - 11:30 AM
  //  [Location]
  //
  //  [Confirm Game]    [Suggest Different Time]
  // ---------------------------------
  //
  // CONFIRMED state:
  // ---------------------------------
  //  Game Scheduled!
  //  vs. [Opponent Team Name]
  //  Saturday, April 5 at 10:00 AM
  //  [Add to Calendar]  <- downloads .ics file
  // ---------------------------------
}
```

---

## `services/api.ts` -- Backend Client

```typescript
// Single API client -- all screens import from here
// In dev: points to localhost:8080
// In beta: points to production URL

const API_BASE = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080';

export const api = {
  auth: {
    requestOtp: (contact: string, channel: 'sms' | 'email') => ...,
    verifyOtp: (contact: string, otp: string) => ...,
    refresh: (refreshToken: string) => ...,
    logout: (refreshToken: string) => ...,
  },
  team: {
    get: (teamId: string) => ...,
    getEvents: (teamId: string) => ...,
    suggestWindows: (teamId: string, params: WindowParams) => ...,
  },
  events: {
    respond: (eventId: string, status: RsvpStatus) => ...,
  },
  negotiation: {
    initiate: (params: InitiateParams) => ...,
    join: (sessionId: string, params: JoinSessionParams, initiatorBaseUrl?: string) => ...,
    getSession: (sessionId: string) => ...,
    propose: (sessionId: string) => ...,
    respond: (sessionId: string, payload: CounterPayload) => ...,
    socketToken: (sessionId: string) => ...,
    confirm: (sessionId: string, slot: TimeSlot) => ...,
    cancel: (sessionId: string) => ...,
  },
  calendar: {
    status: () => ...,
    authorizeUrl: () => ...,
    disconnect: () => ...,
  },
  devices: {
    register: (expoPushToken: string, platform: string) => ...,
    unregister: (expoPushToken: string) => ...,
  },
};
```
