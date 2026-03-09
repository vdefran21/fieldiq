# FieldIQ -- Phase 1 Auth & Calendar Integration

---

## Auth Layer -- Phone OTP (Passwordless)

Parents will not remember passwords. Phone OTP is the right call.

```
Flow:
1. User enters phone number in app
2. Backend checks Redis rate limit (3 per 15min, 10 per 24h per identifier)
3. Backend generates 6-digit OTP, stores hashed version with 10-min expiry in auth_tokens
4. Backend normalizes identifier (lowercase email, E.164 phone), hashes it
   with SHA-256, and stores the hash alongside the OTP hash -- binding the
   token to the requester (see "Identifier Binding" below)
5. OTP sent via Twilio SMS (or email via SendGrid in dev -- cheaper)
6. User enters OTP -> backend verifies hash + identifier hash -> issues:
   - JWT access token (15min expiry)
   - Refresh token (30 days) -- stored hashed in refresh_tokens table
7. Refresh token stored client-side in SecureStore (React Native) -- never in AsyncStorage
8. On refresh: old refresh token is revoked, new one issued (rotation)
9. On logout: refresh token revoked server-side

Dev shortcut: if phone starts with +1555, accept OTP "000000" without
sending SMS. Saves Twilio costs during development.
```

### Identifier Binding (Security)

OTP tokens are bound to the normalized identifier hash of the phone/email that
requested them. During verification, the submitted identifier is normalized and
hashed the same way, and the lookup query includes the identifier hash. This
prevents a valid OTP for phone A from being used to authenticate as phone B.

The identifier is stored as a SHA-256 hash (not plaintext) in the
`identifier_hash` column of `auth_tokens`, so no PII is exposed if the table
is breached. Normalization rules: emails are lowercased and trimmed; phones are
trimmed (already E.164 from input validation).

This is especially important after V4 dropped the UNIQUE constraint on
`token_hash` -- without identifier binding, duplicate OTP hashes (common with
the 6-digit keyspace and dev bypass) could be consumed by any identity on the
same channel.

---

## Google Calendar Integration

```
OAuth Flow:
1. User taps "Connect Google Calendar" in Settings
2. App calls: GET /auth/google/authorize-url and opens the returned Google consent URL in the browser
3. User grants permission
4. Google redirects to: /auth/google/callback?code=...
5. Backend exchanges code for access_token + refresh_token
6. Tokens stored encrypted in calendar_integrations table
7. Backend enqueues one SYNC_CALENDAR task per active team membership immediately
8. Agent worker processes: calls Google FreeBusy API, stores busy blocks
9. Subsequent syncs run every 4 hours via scheduled SQS message

What we pull from Google Calendar:
- Busy blocks only (we never read event titles/details -- privacy matters)
- Stored as availability_windows with source='google_cal', window_type='unavailable'
- Refreshed automatically using stored refresh_token

Scopes needed:
- https://www.googleapis.com/auth/calendar.readonly
  (use FreeBusy endpoint; read-only -- minimal permissions, trust-building)

Phase 1 non-goal: No calendar write-back.
When a game is confirmed, the notification includes an "Add to Calendar"
link that downloads a .ics file. Automatic Google Calendar event creation
requires write scope and is deferred to Phase 2.
```
