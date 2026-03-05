# FieldIQ -- Phase 1 Agent Layer (Node.js/TypeScript)

The agent layer handles async work: calendar sync, LLM calls, SQS message processing. It runs as a separate process. It does NOT own scheduling logic (see Decision 1 in [00_Phase1_Overview.md](00_Phase1_Overview.md)).

---

## `calendar-sync.worker.ts` -- SQS Consumer

```typescript
// workers/calendar-sync.worker.ts
// Triggered: on calendar connect + every 4 hours via scheduled SQS message

interface SyncCalendarTask {
  taskType: 'SYNC_CALENDAR';
  userId: string;
  teamId: string;
}

// 1. Read calendar_integrations for user
// 2. Call Google Calendar FreeBusy API (read-only scope)
// 3. Convert busy blocks to availability_windows (source='google_cal')
// 4. Delete stale google_cal windows, insert fresh ones
// 5. Update last_synced_at
```

---

## `communication.agent.ts` -- Claude Haiku for Message Drafting

```typescript
// Uses Claude Haiku -- fast and cheap (~$0.001 per RSVP message batch)
// Never used for scheduling logic -- only for natural language drafting

export class CommunicationAgent {
  private anthropic: Anthropic;

  async draftEventReminder(params: {
    eventType: 'game' | 'practice';
    teamName: string;
    opponentName?: string;
    startsAt: Date;
    location: string;
    playerName: string;   // personalized per parent
  }): Promise<string> {
    // Prompt: "Draft a friendly 2-sentence reminder for a youth soccer
    //          {game/practice}. Tone: warm, brief, parent-friendly.
    //          Include time and location. Address the parent of {playerName}."
    // Returns plain text -- backend decides SMS vs push vs email
  }

  async draftRsvpFollowUp(params: {
    playerName: string;
    eventDescription: string;
    daysSinceOriginalMessage: number;
  }): Promise<string> {
    // Gentle follow-up for non-responders
    // Escalates urgency slightly if daysSince > 3
  }

  async draftNegotiationOutcome(params: {
    outcome: 'confirmed' | 'no_slots_found';
    agreedTime?: Date;
    agreedLocation?: string;
    teamName: string;
  }): Promise<string> {
    // Notifies manager of negotiation result
    // Includes .ics link for confirmed outcomes
  }
}
```

---

## SQS Worker Pattern

```typescript
// workers/agent-task-worker.ts
// Runs continuously, polls SQS for tasks

interface AgentTask {
  taskType: 'SYNC_CALENDAR' | 'SEND_REMINDERS' | 'SEND_NOTIFICATION';
  payload: Record<string, unknown>;
  teamId: string;
  priority: 'high' | 'normal';
}

// Task types and when they're triggered:
// SYNC_CALENDAR       -> when user connects Google Calendar or every 4 hours
// SEND_REMINDERS      -> 24h and 2h before each event (scheduled by backend)
// SEND_NOTIFICATION   -> push/SMS/email dispatch (enqueued by backend on state changes)
```

**Note:** `RUN_NEGOTIATION_ROUND` is no longer an agent task. Negotiation rounds are orchestrated by the Kotlin backend (`NegotiationService`), which calls `SchedulingService` directly and relays via `CrossInstanceRelayClient`. The agent layer is only involved if an LLM call is needed for ambiguous conflict resolution (Phase 2).
