# FieldIQ Threat Model

Last updated: 2026-03-09

## Scope

This document covers the Phase 1 surfaces that matter most for beta readiness:

- OTP authentication and refresh tokens
- Google Calendar OAuth and sync
- Cross-instance negotiation relay
- Negotiation WebSocket updates
- Agent-side notification delivery

## Assets

- Adult manager identity and phone/email credentials
- Refresh tokens and short-lived JWTs
- Google OAuth tokens stored in `calendar_integrations`
- Negotiation session state, invite tokens, and HMAC session keys
- Expo push tokens in `user_devices`

## Main Trust Boundaries

- Mobile app to backend REST API
- Backend instance A to backend instance B over HMAC-authenticated relay calls
- Backend to SQS queues
- Agent workers to PostgreSQL and external APIs (Google Calendar, Expo push)
- Browser-based OAuth handoff between mobile and Google

## Primary Threats And Controls

### OTP and bearer auth

- Threat: OTP replay, brute force, or cross-identity token use.
- Current controls:
  - Redis-backed OTP rate limiting
  - OTP tokens bound to identifier hash
  - Refresh token rotation and hashed-at-rest storage
- Remaining beta checks:
  - confirm production secrets are rotated out of local defaults
  - monitor repeated OTP failures and refresh-token misuse

### Google Calendar OAuth

- Threat: token theft, over-broad scope, or stale tokens never syncing.
- Current controls:
  - read-only Google scope
  - AES-encrypted tokens at rest
  - browser-based handoff via fetched authorize URL
  - post-connect `SYNC_CALENDAR` task enqueue
- Remaining beta checks:
  - move to the narrowest viable free/busy scope
  - confirm callback and deep-link behavior in the real mobile environment

### Cross-instance negotiation relay

- Threat: forged relay calls, replay attacks, or shadow-session drift.
- Current controls:
  - per-session HMAC signing
  - timestamp drift validation
  - Redis nonce replay prevention
  - explicit state-machine enforcement and idempotent proposal storage
- Remaining beta checks:
  - alert on repeated HMAC failures
  - include correlation IDs in relay logs and incident triage

### Negotiation WebSocket channel

- Threat: leaking long-lived bearer tokens in URLs, unauthorized subscription, or browser-origin misuse.
- Current controls:
  - short-lived negotiation-scoped WebSocket token
  - team-membership authorization in the handshake
  - configurable origin-pattern validation when an `Origin` header is present
- Remaining beta checks:
  - verify token expiry and reconnect behavior on real devices
  - confirm logs never print handshake URLs with secrets

### Notification delivery

- Threat: notification queue not drained, push data delivered to wrong recipients, or leaked device tokens.
- Current controls:
  - queue-based dispatch
  - recipient resolution from active team memberships only
  - Expo push transport with per-device logging on success/failure
- Remaining beta checks:
  - validate delivery on two physical devices
  - decide how to deactivate invalid Expo tokens after transport errors

## Operational Requirements Before External Beta

- Enable GitHub dependency review, CodeQL, and Dependabot updates.
- Ensure GitHub secret scanning and push protection are enabled at the repo/org level.
- Run the full mobile-to-negotiation acceptance path on two instances and two physical devices.
- Verify `/actuator/health` and metrics endpoints are available to deployment monitoring.
