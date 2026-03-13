# FieldIQ Agent Layer

Node.js/TypeScript SQS worker process. Consumes async tasks enqueued by the Kotlin backend. Does **not** expose HTTP endpoints — it is a pure consumer/worker.

For architecture decisions and coding standards, see [CLAUDE.md](../CLAUDE.md). For detailed design, see [docs/specs/phase-1/05-agent-layer.md](../docs/specs/phase-1/05-agent-layer.md).

## What This Does

The agent layer handles tasks that are async, external, or AI-powered:

| Task Type | Worker | Status | Description |
|-----------|--------|--------|-------------|
| `SYNC_CALENDAR` | `calendar-sync.worker.ts` | Implemented | Fetches Google Calendar FreeBusy data, writes availability windows to shared DB |
| `SEND_NOTIFICATION` | `notification.worker.ts` | Implemented (basic) | Resolves registered devices and logs a delivery attempt per device |
| `SEND_REMINDERS` | — | Sprint 6 | AI-drafted reminder messages via Claude Haiku |

The backend enqueues tasks to SQS. The agent polls, dispatches by `taskType`, and writes results to the shared PostgreSQL database. The agent never calls backend REST endpoints.

## Prerequisites

- Node.js 20+
- Docker & Docker Compose running (for LocalStack SQS and Postgres)

## Running

```bash
# Install dependencies
npm ci

# Development (ts-node, no build step)
npm run dev

# Or build and run compiled JS
npm run build
npm start
```

The agent starts an SQS long-polling loop and runs until stopped. It responds to `SIGINT`/`SIGTERM` for graceful shutdown (drains current batch, closes DB pool).

## Testing

### Unit Tests

```bash
npm test            # Run all tests with coverage
npm run test:watch  # Watch mode
```

All external dependencies (SQS, Google APIs, PostgreSQL) are mocked — no live services needed.

### Integration Tests

```bash
npm run test:integration          # Requires Docker infrastructure
npm run test:integration:watch    # Watch mode
```

Integration tests use real Postgres and real LocalStack SQS. Only the Google Calendar API is mocked. Prerequisites:

1. `docker compose up -d` (from repo root)
2. Backend booted at least once to apply Flyway migrations

The test suite verifies prerequisites on startup and fails with a clear message if they're missing.

Two test layers:
- **Worker-level** (`calendar-sync.integration.test.ts`) — calls `handleSyncCalendar()` directly with real DB and real encryption
- **Runtime-level** (`sqs-dispatch.integration.test.ts`) — exercises the full SQS contract via `pollOnce()`: receive, route, delete on success, retry on failure

## Environment Variables

All variables have local development defaults in `src/config.ts`. Set these for non-local environments:

| Variable | Purpose | Default |
|----------|---------|---------|
| `DATABASE_URL` | PostgreSQL connection string | `postgresql://fieldiq:localdev@localhost:5432/fieldiq` |
| `AWS_REGION` | AWS region for SQS | `us-east-1` |
| `AWS_ENDPOINT_URL` | SQS endpoint (LocalStack in dev) | `http://localhost:4566` |
| `AGENT_TASKS_QUEUE_URL` | SQS queue URL to poll | `http://localhost:4566/000000000000/fieldiq-agent-tasks` |
| `GOOGLE_CLIENT_ID` | Google OAuth client ID (for token refresh) | empty |
| `GOOGLE_CLIENT_SECRET` | Google OAuth client secret (for token refresh) | empty |
| `TOKEN_ENCRYPTION_KEY` | AES-256-GCM key for decrypting stored OAuth tokens | dev default (32 chars) |

**Critical:** `TOKEN_ENCRYPTION_KEY` must match the backend's `fieldiq.encryption.token-key`. The backend encrypts OAuth tokens during the Google Calendar OAuth flow; the agent decrypts them when syncing calendars. A mismatch means decryption failures.

## How It Works

1. `index.ts` starts an SQS long-polling loop (20s wait, up to 10 messages per batch)
2. Each message is parsed as JSON with a `taskType` field
3. The dispatcher routes to the appropriate worker function
4. Workers read/write the shared PostgreSQL database directly
5. On success, the SQS message is deleted. On failure, it returns to the queue after the visibility timeout

### Calendar Sync Flow

When the backend enqueues a `SYNC_CALENDAR` task (e.g., after a user connects Google Calendar):

1. Load encrypted OAuth tokens from `calendar_integrations` table
2. Decrypt access token using AES-256-GCM (must match backend's encryption)
3. If expired, refresh via Google's token endpoint and update the DB
4. Call Google Calendar FreeBusy API (30-day look-ahead, primary calendar only)
5. Delete existing `google_cal` availability windows for this user
6. Insert fresh busy blocks as `availability_windows` with `source='google_cal'`, `window_type='unavailable'`
7. Update `last_synced_at` on the integration record

## Project Structure

```
agent/
├── package.json
├── tsconfig.json
├── jest.config.js                  # Unit test config
├── jest.integration.config.js      # Integration test config
├── src/
│   ├── index.ts                    # Entry point — thin bootstrap, polling loop
│   ├── task-dispatcher.ts          # Runtime logic — dispatchTask, processMessage, pollOnce
│   ├── config.ts                   # Environment config with defaults
│   ├── db.ts                       # PostgreSQL connection pool (max 5 connections)
│   ├── encryption.ts               # AES-256-GCM token decryption (matches backend format)
│   ├── workers/
│   │   ├── calendar-sync.worker.ts # SYNC_CALENDAR handler
│   │   └── notification.worker.ts  # SEND_NOTIFICATION handler
│   ├── __tests__/                  # Unit tests (all deps mocked)
│   │   ├── calendar-sync.worker.test.ts
│   │   ├── notification.worker.test.ts
│   │   └── encryption.test.ts
│   └── __integration__/            # Integration tests (real Postgres + SQS)
│       ├── setup/
│       │   ├── global-setup.ts     # Prerequisite verification
│       │   ├── global-teardown.ts  # Pool cleanup
│       │   ├── test-helpers.ts     # DB factory functions
│       │   ├── test-sqs.ts         # SQS send/purge helpers
│       │   └── test-encryption.ts  # Backend-compatible encrypt helper
│       ├── calendar-sync.integration.test.ts
│       └── sqs-dispatch.integration.test.ts
└── dist/                           # Compiled output (npm run build)
```

## Troubleshooting

**Agent can't connect to SQS**
LocalStack must be running (`docker compose up -d`). Check that the queue exists: `aws --endpoint-url=http://localhost:4566 sqs list-queues`.

**Token decryption fails**
`TOKEN_ENCRYPTION_KEY` must match the backend's `fieldiq.encryption.token-key` exactly. The key is truncated to 32 bytes — if they differ by even one character, decryption will throw.

**Agent processes same message repeatedly**
If a worker throws, the SQS message isn't deleted and reappears after the visibility timeout. Check the worker logs for the root error.

**No messages being processed**
The backend must enqueue tasks. Calendar sync is triggered when a user connects Google Calendar via the OAuth flow. Check the SQS queue depth: `aws --endpoint-url=http://localhost:4566 sqs get-queue-attributes --queue-url http://localhost:4566/000000000000/fieldiq-agent-tasks --attribute-names ApproximateNumberOfMessages`.
