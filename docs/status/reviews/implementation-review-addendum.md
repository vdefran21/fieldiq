# FieldIQ — Implementation Review Addendum
**Addendum to:** [`../next-steps.md`](../next-steps.md)  
**Companion status source:** [`../implementation-tracking.md`](../implementation-tracking.md)  
**Last updated:** 2026-03-13

---

## Purpose

This document supplements `next-steps.md` with a standards-based review of the current Phase 1 implementation. It does **not** replace the existing roadmap. Its purpose is to answer a narrower question:

**Are we on the right track technically and operationally, and what must change before FieldIQ is truly beta-ready?**

The short answer is **yes on direction, no on beta readiness**.

---

## Executive Assessment

| Area | Assessment | Notes |
|---|---|---|
| Architecture | **Strong** | The core product boundary is correct: deterministic scheduling and negotiation live in the Kotlin backend; the agent layer handles async work, calendar sync, and communication drafting. [C] |
| Technical de-risking | **Strong** | The hardest Phase 1 uncertainty—the cross-instance negotiation protocol—appears materially de-risked by the current tracker and test evidence. [A][C][F] |
| Implementation discipline | **Above average for Phase 1** | Migration-first schema, explicit architecture decisions, CI, integration tests, and remediation tracking are all good signals. [A][C][F] |
| Product loop | **Incomplete** | The current `next-steps.md` is correct: the app still needs a user-visible path from first-team creation into real scheduling work. [B] |
| Security baseline | **Good, not finished** | OTP binding, hashed refresh tokens, encrypted calendar tokens, and HMAC relay are all sound; WebSocket auth, DevSecOps automation, and defense-in-depth still need work. [C][D][E] |
| Beta readiness | **Not yet** | Push transport, mobile core loop, observability, and hardening are not complete enough for external pilots. [A][B] |
| Strategic focus | **Correct** | The roadmap’s emphasis on shipping the cross-team protocol before broader expansion remains the right call. [G] |

### Bottom line

FieldIQ is **on the right track**. The main risk is no longer “can the protocol work?” The main risk is now **can the product close the visible manager workflow and harden the system enough for external trust?**

That is a much better problem to have.

---

## What FieldIQ Is Doing Well

### 1. The architecture matches the product’s real moat

The current architecture keeps scheduling and protocol orchestration deterministic and centralized in the backend, while reserving the agent layer for async work and optional LLM-assisted messaging. That is the correct boundary for a product whose primary value is **reliable coordination**, not “AI vibes.” [C][H]

This also matches the business thesis: the differentiator is the cross-instance negotiation protocol, not generic assistant behavior. [G]

### 2. The team is sequencing the hard thing first

The plan explicitly puts the cross-team protocol inside the MVP rather than deferring it, and the vertical-slice milestone correctly says not to proceed to mobile unless the protocol works end to end. [C]

That is the right sequencing decision for FieldIQ. If the protocol is the moat, it has to be proven early.

### 3. The security baseline is credible for a Phase 1 build

The current design includes:

- OTP identifier binding and rate limiting. [E]
- Hashed refresh tokens and rotation. [E]
- Encrypted calendar tokens at rest. [E]
- HMAC-signed cross-instance relay calls with nonce-based replay protection. [C][I]
- Secure token storage on device using SecureStore. [9]

That is materially better than the security posture of many early-stage prototypes.

### 4. The testing posture is strong for this stage

Current docs and tracking show:

- CI in GitHub Actions. [F]
- Real Postgres / TestContainers instead of H2 for backend integration tests. [F]
- In-process two-instance negotiation protocol tests. [F]
- Explicit remediation tracking instead of hidden bug debt. [A]

This is a real engineering process, not a fragile demo stack.

---

## Where FieldIQ Is Still Below Best Practice

### 1. The core manager workflow is still incomplete

`next-steps.md` is directionally correct: the app currently stalls after first-team creation because the schedule empty state still needs to lead directly into real scheduling work. [B]

That means the system has proven backend capability, but not yet the user-visible product loop:

1. login  
2. create team  
3. start negotiation  
4. share / join  
5. receive updates  
6. confirm  
7. see resulting event

Until that path is complete, Phase 1 is technically impressive but not yet product-complete.

### 2. Push notifications still need real-device proof

The tracker is explicit that backend device registration exists, and the worker now calls Expo’s push service instead of logging-only delivery attempts. [A]

The remaining gap is validation, not transport implementation. Expo’s own documentation still requires real device-side setup and credentials, and push notifications are **not supported on iOS simulators or Android emulators**. [8]

**Required change:** treat Sprint 7 acceptance as **two physical devices**, not “two simulators,” and record real delivery evidence and failure handling.

### 3. WebSocket authentication is acceptable for local validation, but not ideal for beta

The implementation has already improved from long-lived bearer JWTs to a short-lived negotiation-scoped WebSocket token exchanged over REST, and it now validates `Origin` when present. [A][D] That is a meaningful security improvement.

The remaining concern is that the handshake token still travels in the WebSocket URL query string, which can appear in access logs if not redacted. OWASP’s WebSocket guidance also recommends explicit token handling, periodic re-validation, and expiry-aware session lifecycle management. [4]

**Recommendation before beta:**
- keep the short-lived WS-specific token model;
- redact handshake URLs / query params from logs;
- close WS sessions on logout / expiry.

### 4. Calendar scope and mobile OAuth flow should be tightened

Phase 1 currently uses read-only Google Calendar scope and no write-back, which is directionally correct. [C] However, Google exposes narrower free/busy scopes than `calendar.readonly`, including `calendar.freebusy` and `calendar.events.freebusy`. [5]

For native OAuth, RFC 8252 says native-app authorization requests should use the external browser, and Expo’s AuthSession docs are aligned with browser-based OAuth flows. [6][7]

**Recommendation before wider beta:**
- narrow scope from `calendar.readonly` to the smallest viable free/busy scope;
- implement mobile Google OAuth with browser-based AuthSession + PKCE;
- keep write-back deferred until there is a concrete user need.

### 5. Multi-tenancy is good at the app layer, but not yet defense-in-depth

The project already has explicit app-layer authorization through `TeamAccessGuard`, and that is the correct Phase 1 starting point. [D] OWASP’s API Security guidance is clear that object-level authorization must be applied anywhere object IDs are exposed. [3]

However, Postgres RLS is still deferred. [D] Official PostgreSQL documentation supports row security as a native mechanism for per-row access control. [11]

**Recommendation:** if Phase 2 beta means multiple organizations on a shared production database, Row Level Security should move from “future hardening” to **pre-beta recommendation**.

### 6. DevSecOps automation is improving, but still behind industry baseline

The current CI is good, and the repo now includes baseline guardrails such as dependency review, Dependabot, CodeQL, and a threat model. [A][F] The remaining gap is turning those pieces into a clearly enforced beta release lane. NIST SSDF and NIST IR 8397 still push toward automated, repeatable verification, including threat modeling, static analysis, dynamic testing, coverage analysis, fuzzing where appropriate, and continuous integration into the engineering workflow. [1][2]

GitHub’s native tooling can close a meaningful part of this gap:

- Dependency Review Action for PR enforcement. [12]
- Secret scanning for committed credentials. [13]
- Dependabot alerts and security / version updates. [14]

**Recommendation:** finish a minimum DevSecOps lane before external beta:
- enforce dependency review on PRs;
- enable secret scanning and push protection;
- keep Dependabot security + version updates active;
- confirm at least one SAST path for Kotlin/TypeScript remains green;
- keep the threat model current as security decisions land.

### 7. Observability has started, but is still under-specified for beta

The backend already includes Spring Boot Actuator in dependencies, and the tracker records early health / metrics exposure plus correlation IDs. [A][D] Spring’s production and observability docs still emphasize health, metrics, and tracing support for production monitoring. [10]

For a system that includes:
- dual instances,
- async queues,
- WebSockets,
- push dispatch,
- cross-instance protocol state,

you need correlation and visibility across the whole path.

**Recommendation before beta:**
- finish `/actuator/health` and readiness validation;
- extend correlation IDs across negotiation sessions and notification tasks end to end;
- emit structured logs for session lifecycle, relay calls, notification attempts, and failures;
- instrument queue lag, push success/failure, negotiation time-to-match, and session failure reasons.

### 8. Data minimization policy needs a cleaner decision on minors’ data

The business plan says COPPA mitigation is to store parent/manager data only and not store player PII in agent communication. [G] But the schema still includes `team_members.player_name`, and the CommunicationAgent spec still references `playerName` for personalized messages. [E][H]

This is not automatically a legal problem on its own, but it **is** a policy inconsistency.

**Recommendation before beta:** make an explicit product/legal decision on one of these paths:
- keep player names, but document the legal basis, product need, and handling constraints; or
- remove / minimize them from schema and prompts until a later phase.

---

## Revised Priority Order

This should be treated as the standards-based extension of `next-steps.md`, not a replacement for it.

### P0 — Must happen before external beta

1. **Validate the core loop end to end**  
   Run the existing mobile flow from login through confirmed event creation, then fix the bugs and friction that appear in Sprint 7. [B]

2. **Validate Expo push delivery on real devices**  
   Keep the current transport, but prove negotiation and confirmation pushes on two physical devices. [A][8]

3. **Finish WebSocket hardening**  
   Keep the short-lived socket-token exchange, add log redaction, and close websocket sessions on logout / expiry. [4][D]

4. **Tighten Google Calendar permissions and OAuth flow**  
   Use the smallest viable scope and browser-based native OAuth flow. [5][6][7]

5. **Finish minimum DevSecOps guardrails**  
   Threat model, dependency review, secret scanning, Dependabot, and at least one SAST lane. [1][2][12][13][14]

6. **Finish production observability**  
   Health checks, metrics, tracing/correlation, and structured logs. [10]

7. **Resolve the minors-data policy mismatch**  
   Decide whether `player_name` is intentionally in scope and document the policy. [E][G][H]

8. **Decide whether Postgres RLS is pre-beta**  
   Recommendation: yes if clubs/orgs will share the same production database. [3][11]

### P1 — Important after the core loop is working

1. CommunicationAgent delivery for reminders/outcomes. [A][H]  
2. Team B fallback flow when the responder is not on FieldIQ. [A][G]  
3. RSVP/event detail only if it directly supports the north-star scheduling loop. [B]  
4. Time-saved instrumentation from initiate → confirmed. [A][G]

### P2 — Explicitly defer until after P0/P1

1. Season/team switching polish. [B]  
2. Club admin dashboard. [G]  
3. Billing and Stripe activation. [G]  
4. Tournament features. [G]  
5. Android and other expansion work. [G]

---

## Pre-Beta Release Gates

FieldIQ should **not** move into external pilot mode until all of the following are true:

### Product gates

- Fresh user can log in, create a first team, and start negotiation from the app. [B]
- Team B can join from the second instance without manual back-channel setup. [B][C]
- Both managers receive negotiation status updates and can confirm the matched slot. [B][I]
- Confirmed negotiation creates visible events and exposes `.ics`. [A][B]

### Device / delivery gates

- Push delivery succeeds on **two physical devices**. [8]
- Missing local config remains non-fatal in development, but production transport is real. [A]

### Security gates

- Threat model documented for auth, calendar integration, WebSocket, negotiation relay, and notification flow. [1][2]
- Secret scanning, dependency review, and Dependabot are enabled. [12][13][14]
- WebSocket handshake tokens are short-lived, log-redacted, and closed on logout / expiry. [4]
- Decision made on RLS and minors-data handling. [3][11][G][H]

### Operations gates

- Health endpoint, structured logs, and correlation IDs are live. [10]
- Failure paths are observable: relay failure, push failure, timeout, duplicate proposal, expired invite. [C][I]

---

## Recommended Doc Updates

### Keep `../next-steps.md` aligned
It should stay focused on end-to-end validation, physical-device push proof, and the remaining beta gates rather than on already-shipped mobile entrypoints.

### Keep `../../specs/phase-1/00-overview.md` aligned
Sprint 7 acceptance should remain phrased as **two physical devices** for push validation. [A][8]

### Keep `../../specs/phase-1/03-backend.md` aligned
Document the short-lived `socket-token` exchange plus remaining log-redaction / expiry hardening. [D][4]

### Update `../../specs/phase-1/02-auth-calendar.md`
Replace broad read-only wording with the narrowest acceptable Google free/busy scope and explicitly document browser-based native OAuth. [5][6][7]

### Update `../../specs/phase-1/07-ci-testing.md`
Add a security automation section covering dependency review, secret scanning, Dependabot, SAST, and release gating. [1][2][12][13][14]

### Keep the threat-model doc current
The repo now has `docs/security/threat-model.md`. Keep it synchronized with auth, WebSocket, relay, calendar, push, and tenant-isolation decisions as beta gates change.

---

## Final Decision

Continue Phase 1 on the current architecture.

Do **not** broaden scope.

Treat the next stage as:

**core loop completion + beta hardening + observability + security discipline**

—not feature expansion.

That is the fastest path to a credible external pilot and the best way to protect the actual moat.

---

## References

### Internal project documents

- **[A]** [`../implementation-tracking.md`](../implementation-tracking.md)
- **[B]** [`../next-steps.md`](../next-steps.md)
- **[C]** [`../../specs/phase-1/00-overview.md`](../../specs/phase-1/00-overview.md)
- **[D]** [`../../specs/phase-1/03-backend.md`](../../specs/phase-1/03-backend.md)
- **[E]** [`../../specs/phase-1/01-schema.md`](../../specs/phase-1/01-schema.md)
- **[F]** [`../../specs/phase-1/07-ci-testing.md`](../../specs/phase-1/07-ci-testing.md)
- **[G]** [`../../product/fieldiq-business-plan-2026.md`](../../product/fieldiq-business-plan-2026.md)
- **[H]** [`../../specs/phase-1/05-agent-layer.md`](../../specs/phase-1/05-agent-layer.md)
- **[I]** [`../../specs/phase-1/08-architecture-diagrams.md`](../../specs/phase-1/08-architecture-diagrams.md)

### External standards and official guidance

- **[1]** NIST SP 800-218 — Secure Software Development Framework (SSDF) Version 1.1  
  <https://nvlpubs.nist.gov/nistpubs/specialpublications/nist.sp.800-218.pdf>
- **[2]** NIST IR 8397 — Guidelines on Minimum Standards for Developer Verification of Software  
  <https://nvlpubs.nist.gov/nistpubs/ir/2021/NIST.IR.8397.pdf>
- **[3]** OWASP API Security Top 10 2023 — API1: Broken Object Level Authorization  
  <https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/>
- **[4]** OWASP WebSocket Security Cheat Sheet  
  <https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html>
- **[5]** Google Calendar API scopes  
  <https://developers.google.com/workspace/calendar/api/auth>
- **[6]** RFC 8252 — OAuth 2.0 for Native Apps  
  <https://www.rfc-editor.org/info/rfc8252>
- **[7]** Expo AuthSession / authentication guides  
  <https://docs.expo.dev/versions/latest/sdk/auth-session/>  
  <https://docs.expo.dev/guides/authentication/>
- **[8]** Expo push notifications setup  
  <https://docs.expo.dev/push-notifications/push-notifications-setup/>
- **[9]** Expo SecureStore  
  <https://docs.expo.dev/versions/latest/sdk/securestore/>
- **[10]** Spring Boot Actuator / observability / tracing  
  <https://docs.spring.io/spring-boot/reference/actuator/index.html>  
  <https://docs.spring.io/spring-boot/reference/actuator/observability.html>  
  <https://docs.spring.io/spring-boot/reference/actuator/tracing.html>
- **[11]** PostgreSQL Row Security Policies  
  <https://www.postgresql.org/docs/current/ddl-rowsecurity.html>
- **[12]** GitHub dependency review  
  <https://docs.github.com/en/code-security/concepts/supply-chain-security/about-dependency-review>
- **[13]** GitHub secret scanning  
  <https://docs.github.com/en/code-security/concepts/secret-security/about-secret-scanning>
- **[14]** GitHub Dependabot quickstart / alerts  
  <https://docs.github.com/en/code-security/tutorials/secure-your-dependencies/dependabot-quickstart-guide>
