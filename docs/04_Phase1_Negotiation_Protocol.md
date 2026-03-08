# FieldIQ -- Phase 1 Cross-Team Negotiation Protocol

This is the IP. Here's the complete flow design.

---

## Protocol Flow

```
TEAM A (initiator)                          TEAM B (responder)
on FieldIQ Instance A                       on FieldIQ Instance B (or same instance)

1. Manager A hits POST /negotiations
   - Creates negotiation_session (status: pending_response)
   - Generates invite_token (cryptographically random, 48h TTL)
   - Sends invite to Team B manager via SMS/email
     containing a deep-link with the invite_token

2. Manager B opens deep-link -> joins session
   POST /negotiations/:id/join (called on Instance A, initiated by Manager B from Instance B's app)
   - Validates invite_token (single-use -- consumed on join)
   - Instance A bootstraps a shadow session on Instance B via
     POST /api/negotiate/incoming before consuming the token
   - Both instances derive session HMAC key from invite_token
   - Status transitions: pending_response -> proposing
   - If Team B is NOT on FieldIQ: fallback to manual
     (they see available windows, pick one, confirm)

3. Instance A runs SchedulingService:
   - Queries availability_windows for Team A members
   - Merges with Google Calendar busy blocks (from last sync)
   - Computes available windows for Team A within date range
   - Sends top 5 candidate slots to Instance B
   POST /api/negotiate/:sessionId/relay (on Instance B)
   - Request includes HMAC signature (see auth scheme)
   - Only aggregated proposals are shared -- never raw member availability

4. Instance B runs SchedulingService:
   - Computes Team B availability
   - Intersects with proposed slots from Instance A
   - If intersection found -> sends matched slots back
   - If no intersection -> counters with Team B's top 5 windows
   - current_round incremented

5. Repeat steps 3-4 up to max_rounds (3 by default)
   Each round, window sets narrow toward mutual availability

6. When match found:
   - Both instances set status = 'pending_approval'
   - Push notification to both managers via Expo push
   - WebSocket update sent to connected clients
   - Managers see: "We found a time: Saturday April 5 at 10am. Confirm?"

7. Both managers confirm -> status = 'confirmed'
   - Event created in FieldIQ for each team
   - Confirmation messages sent to all team members
   - .ics download link included in notification
   - (Phase 2) Optional write-back to Google Calendar

FALLBACK (Team B not on FieldIQ):
   - Instance A sends a simple scheduling link to Team B manager
   - They see Team A's available windows (no AI -- just a picker)
   - They select a time -> confirmation sent to both managers
   - This is still better than texting back and forth
```

---

## Allowed State Transitions

```
pending_response -> proposing      (responder joins)
pending_response -> cancelled      (initiator withdraws)
pending_response -> failed         (invite expires)

proposing -> pending_approval      (match found)
proposing -> failed                (max_rounds exceeded or timeout)
proposing -> cancelled             (either side withdraws)

pending_approval -> confirmed      (both managers confirm)
pending_approval -> proposing      (one side requests different time)
pending_approval -> cancelled      (either side withdraws)

confirmed -> (terminal)
failed -> (terminal)
cancelled -> (terminal)
```

---

## Cross-Instance Request/Response Contract

**Incoming bootstrap request (Instance A -> Instance B before relay traffic):**
```
POST /api/negotiate/incoming
Body:
{
  "sessionId": "uuid",
  "inviteToken": "single-use-bearer-secret",
  "initiatorTeamId": "uuid",
  "initiatorInstance": "http://localhost:8080",
  "responderTeamId": "uuid",
  "responderInstance": "http://localhost:8081",
  "requestedDateRangeStart": "2026-04-01",
  "requestedDateRangeEnd": "2026-04-15",
  "requestedDurationMinutes": 90,
  "maxRounds": 3,
  "expiresAt": "2026-04-03T18:30:00Z"
}
```

`/api/negotiate/incoming` is intentionally excluded from HMAC validation because the
responder-side local session does not exist yet. The invite token acts as the bearer
credential for this bootstrap call; after the shadow session is created, all subsequent
relay traffic uses the derived HMAC session key.

**Relay request (Instance A -> Instance B):**
```
POST /api/negotiate/:sessionId/relay
Headers:
  Content-Type: application/json
  X-FieldIQ-Session-Id: <session UUID>
  X-FieldIQ-Timestamp: <ISO-8601 UTC>
  X-FieldIQ-Signature: <HMAC-SHA256 hex>
  X-FieldIQ-Instance-Id: <sender's instance ID>

Body:
{
  "action": "propose" | "respond" | "confirm" | "cancel",
  "roundNumber": 2,
  "proposalId": "uuid",          // idempotency key
  "actor": "initiator" | "responder",
  "slots": [
    {
      "startsAt": "2026-04-05T14:00:00Z",
      "endsAt": "2026-04-05T15:30:00Z",
      "location": "Bethesda Soccer Complex Field 3"
    }
  ],
  "responseStatus": "accepted" | "rejected" | "countered",  // for respond action
  "rejectionReason": "no_availability"                        // optional
}
```

**Relay response:**
```json
{
  "status": "received",
  "sessionStatus": "proposing",
  "currentRound": 2,
  "agreedStartsAt": "2026-04-05T14:00:00Z",
  "agreedEndsAt": "2026-04-05T15:30:00Z",
  "agreedLocation": "Bethesda Soccer Complex Field 3"
}
```

**Error responses:**
```json
{
  "error": "invalid_signature" | "session_not_found" | "invalid_state_transition" | "expired",
  "message": "Human-readable explanation"
}
```

---

## HMAC Signature Scheme

**Signature computation:**
```
message = sessionId + timestamp + requestBodyString
signature = HMAC-SHA256(sessionKey, message)
sessionKey = HMAC-SHA256(instanceSecret, inviteToken)
```

**Validation rules:**
- Reject if `|server_time - timestamp| > 5 minutes`
- Reject if signature does not match
- **Replay protection:** Store `HMAC-SHA256(signature)` as a nonce in Redis with a 5-minute TTL (`SET fieldiq:nonce:<hash> 1 EX 300 NX`). Reject if the nonce already exists. This prevents replay attacks within the timestamp window at negligible cost.
- Reject if `(session_id, round_number, proposed_by)` already exists (idempotency)
- Reject if state transition is not allowed per the state machine

---

## Error Handling and Retries for Cross-Instance Calls

Cross-instance relay calls may fail due to network issues. Strategy:

1. **Caller retries with exponential backoff:** 3 attempts, delays of 2s / 8s / 30s
2. **Idempotency via unique constraint on `(session_id, round_number, proposed_by)`:** duplicate proposals are safely rejected
3. **Delivery tracking:** `negotiation_proposals` has a `response_status` field; proposals stuck in `pending` for >10 minutes trigger a retry via SQS
4. **Dead letter:** after 3 failed attempts, session transitions to `failed` with reason `delivery_failure` and both managers are notified
5. **No synchronous blocking:** the relay call returns `202 Accepted` immediately; processing happens async. The caller polls or receives a WebSocket update.
