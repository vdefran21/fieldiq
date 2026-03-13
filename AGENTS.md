# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Testing Philosophy

When Bruno API tests or any integration tests fail, **never modify backend code just to make a test pass**. Always:
1. Consult the implementation guides in `docs/` to determine the expected behavior.
2. Confirm whether the test expectation matches the spec before changing anything.
3. If the backend behavior matches the docs, fix the test.
4. If the backend behavior contradicts the docs, fix the backend.
5. If the docs are ambiguous, flag it for user review before making changes.

The docs are the source of truth for expected behavior — not the tests, and not the current code.

## GENERAL DEVELOPMENT

When running in "bypass permissions" or "auto accept edits" modes, be sure to stop at reasonable intervals for user review and potential commit events.

### DO NOT
 - commit or push changes to git 
 - kill processes (pids)
 - attempt to run the application yourself
   - if you need the application running, notify the user what needs to be running and in what capacity

## Project Overview

FieldIQ is an AI-powered youth sports management platform. The core differentiator is a **cross-team scheduling negotiation protocol** — two FieldIQ instances autonomously negotiate game times between teams on different instances.

Phase 1 target: working cross-team scheduling negotiation demo + iOS MVP for DMV youth soccer.

## Tech Stack

- **Backend:** Kotlin Spring Boot (Java 21) — owns all scheduling and negotiation logic
- **Agent Layer:** Node.js/TypeScript — async SQS workers for calendar sync, LLM message drafting (Codex Haiku), notification dispatch
- **Mobile:** React Native (Expo) with Expo Router — iOS only in Phase 1
- **Database:** PostgreSQL 16 (Flyway migrations)
- **Infrastructure:** Redis (cache/sessions), AWS SQS via LocalStack (async tasks), WebSocket (real-time negotiation updates)
- **Shared types:** TypeScript interfaces in `shared/types/` — Kotlin DTOs maintained manually to match (no codegen)

## Repository Structure

```
backend/          Kotlin Spring Boot API (scheduling, negotiation, auth, REST, WebSocket)
agent/            Node.js/TypeScript SQS workers + Codex Haiku integration
mobile/           React Native Expo app (iOS)
shared/types/     TypeScript API contract interfaces
docs/             Specs, status, security guidance, product context, archived plans
infra/            LocalStack init scripts
```

## Development Commands

```bash
# Start local infrastructure (two Postgres instances, Redis, LocalStack)
docker compose up -d

# Run backend Instance A (port 8080)
SPRING_PROFILES_ACTIVE=instance-a ./gradlew bootRun

# Run backend Instance B (port 8081) — for cross-team negotiation testing
SPRING_PROFILES_ACTIVE=instance-b ./gradlew bootRun

# Backend tests (uses TestContainers with real Postgres, not H2)
cd backend && ./gradlew test

# Agent layer
cd agent && npm ci && npm test

# Mobile
cd mobile && npx expo start
```

## Architecture Decisions (Locked)

1. **Scheduling logic lives in Kotlin backend** — `SchedulingService.kt` (window computation) and `NegotiationService.kt` (protocol orchestration). The agent layer does NOT own scheduling. Agent layer owns: calendar sync worker, CommunicationAgent (Haiku message drafting), notification dispatch.

2. **Cross-instance auth uses HMAC-SHA256** — `invite_token` for join handshake, then per-session key derived as `HMAC-SHA256(instance_secret, invite_token)`. Headers: `X-FieldIQ-Session-Id`, `X-FieldIQ-Timestamp`, `X-FieldIQ-Signature`.

3. **Two-instance local dev** — `docker-compose.yml` provides `postgres` (5432) and `postgres-team-b` (5433). Spring profiles `instance-a` and `instance-b` configure DB URL, port, and instance ID.

4. **No calendar write-back in Phase 1** — Google Calendar is read-only (`calendar.readonly` scope). Confirmed games provide `.ics` download links.

5. **Backend → Agent communication is SQS only** — Backend enqueues tasks; agent layer consumes. Agent never calls backend REST endpoints. Agent reads/writes shared DB for results.

## Key Backend Services

- `TeamAccessGuard` — enforces multi-tenancy. Every controller must call this before accessing team resources.
- `SchedulingService` — deterministic window computation (no LLM). Finds available windows, intersects two teams' windows.
- `NegotiationService` — orchestrates the cross-team protocol state machine: `pending_response → proposing → pending_approval → confirmed/failed/cancelled`.
- `CrossInstanceRelayClient` — WebFlux HTTP client with HMAC signature generation for instance-to-instance calls.

## Database

- All schema changes via Flyway migrations in `backend/src/main/resources/db/migration/`
- Three initial migrations: `V1__initial_schema.sql` (core tables), `V2__negotiation_schema.sql` (negotiation protocol), `V3__rate_limiting.sql` (OTP rate limits)
- OAuth tokens in `calendar_integrations` are encrypted at rest via `TokenEncryptionConverter` (AES-256-GCM)
- OTP rate limits enforced via Redis in real-time, persisted to DB for audit

## Testing

- Backend: JUnit 5 + MockK for unit tests, TestContainers (real Postgres) for integration tests
- Negotiation protocol tests: two `NegotiationService` instances wired to different DataSources in a single test class (in-process, no HTTP)
- Agent: Jest with mocked Anthropic API, SQS, and DB
- CI: GitHub Actions with separate workflows for backend, agent, and mobile (path-filtered)
- Redis and SQS are mocked in test profile (`SPRING_PROFILES_ACTIVE=test`)

## Cross-Team Negotiation Protocol

The core IP. State machine flow:
1. Manager A initiates negotiation → creates session with `invite_token`
2. Manager B joins via `invite_token` → token consumed, session key derived
3. Both instances exchange slot proposals (up to `max_rounds`, default 3)
4. On match → `pending_approval` → both managers confirm → events created on both teams
5. Proposals are idempotent: unique constraint on `(session_id, round_number, proposed_by)`

## Implementation Tracking

> **THIS IS MANDATORY.** Before ending any session — whether pausing, stopping, or completing work —
> you MUST update `docs/status/implementation-tracking.md` to reflect the current state of all tasks.
> This is the single source of truth for project progress.

**File:** `docs/status/implementation-tracking.md`

**When to update:**
- **Before every stop/pause:** Mark tasks completed (✅), in progress (🔧), or not started (⬜). Fill in the "Evidence / Notes" column with file paths or details for any task you touched.
- **After completing a task:** Immediately update the status and evidence column for that task.
- **After adding new code:** If the work maps to a tracked task, update it. If it doesn't map to any existing task, add a row.
- **Progress Summary table:** Update the "Tasks Done" counts in the summary table at the bottom of the file whenever task statuses change.

**What to record in "Evidence / Notes":**
- File paths created or modified (e.g., `backend/src/main/kotlin/com/fieldiq/api/AuthController.kt`)
- Test results (e.g., "14/14 tests passing")
- Blockers or issues encountered
- Anything the next session needs to know to pick up where you left off

**Do not skip this.** Stale tracking is worse than no tracking.

## Test Maintenance

> **THIS IS MANDATORY.** Any code change that affects backend behavior MUST include
> corresponding test updates — both unit tests and Bruno integration tests — **in the
> same session**, before moving on to the next sprint or task.

### When to update tests

The following changes require updating **both** unit tests (`backend/src/test/`) **and** Bruno integration tests (`backend/bruno/`):

- Changing a DTO field (adding, removing, renaming, changing nullability)
- Changing HTTP status codes (e.g., 400 → 422, 403 → 401)
- Changing error response format or envelope structure
- Changing Jackson serialization config (e.g., `non_null`, date formats)
- Changing Spring Security behavior (auth entry points, filter chain)
- Changing database constraints that affect what the API can accept or return
- Changing validation rules (`@Valid`, `@Pattern`, custom validators)
- Changing service-layer logic (business rules, exception types, return values)
- Changing repository query methods or signatures

### Process

1. Make the backend code change.
2. Run `cd backend && ./gradlew test` to identify any broken unit tests.
3. Run `cd backend/bruno && npm test` to identify any broken Bruno integration tests.
4. For each failure, follow the Testing Philosophy (consult docs first — fix the test or the backend accordingly).
5. Verify **all** unit tests and **all** Bruno tests pass before considering the task complete.
6. Update `docs/status/implementation-tracking.md` documenting what changed and why.

**Do not defer this.** Test drift creates false confidence. Passing unit tests with failing integration tests means the API contract is broken from the client's perspective. Passing integration tests with failing unit tests means internal logic has diverged from expectations.

## Important Conventions

- When changing API response shapes, update both the Kotlin DTO and the corresponding TypeScript interface in `shared/types/` in the same commit
- No child PII in `users` table (COPPA). Only parent/coach data. Child names only in `team_members.player_name`
- Dev OTP bypass: `+1555*` phone numbers are exempt from rate limiting
- WebSocket is server-to-client push only in Phase 1 — all mutations go through REST

---

## Documentation Standards (Repository-Wide)

> **THIS IS NOT OPTIONAL.** Every code change — new code, refactored code, changed signatures,
> renamed parameters, modified behavior — MUST include corresponding documentation updates in the
> same commit. Stale documentation is worse than no documentation. If you change how something
> works, update the documentation to reflect the current state before committing.

Use the native documentation style for the language, but hold every language to the same bar:
- **Kotlin:** KDoc
- **TypeScript / JavaScript:** JSDoc or TSDoc
- **SQL:** header comments plus inline design notes where needed
- **YAML / JSON / shell / config files:** concise explanatory comments when the format allows it
- **Tests:** doc comments and high-value explanatory comments for business context, fixtures, and non-obvious assertions

### What Must Be Documented

**Every** public and internal class, function, method, type alias, interface, enum, property, and
significant module-level constant must be documented. No exceptions. The exact syntax may vary by
language, but the content standard does not.
This includes:
- Unit tests
- Domain entities (explain what the table represents, relationships, constraints)
- Repository interfaces (explain query semantics, what "find by X" actually returns)
- Service classes (explain business logic, orchestration role, side effects)
- Controller endpoints (explain request/response contract, auth requirements)
- Configuration classes (explain what they configure and why)
- Data classes / DTOs (explain what each field means in business terms)
- Extension functions and utilities
- TypeScript modules, helper types, worker payloads, and test fixtures
- Queue message shapes, environment/config objects, and integration-test setup helpers

### KDoc Format

```kotlin
/**
 * Brief one-line summary of what this class/function does.
 *
 * More detailed explanation including:
 * - WHY this exists (business context, not just "it does X")
 * - HOW it fits into the larger architecture
 * - Key behavior or side effects (DB writes, SQS messages, etc.)
 * - Important constraints or invariants
 * - Thread safety / concurrency considerations if relevant
 *
 * Example usage (when non-obvious):
 * ```
 * val guard = TeamAccessGuard(repo)
 * val member = guard.requireManager(userId, teamId) // throws if not manager
 * ```
 *
 * @property propertyName Description of constructor property (for data classes)
 * @param paramName Description of parameter including valid values/ranges
 * @return Description of return value, including null semantics
 * @throws ExceptionType When and why this exception is thrown
 * @see RelatedClass for cross-references to related components
 */
```

### TypeScript / JavaScript Documentation Format

```ts
/**
 * Brief one-line summary of what this module, function, interface, or constant does.
 *
 * Include the same level of rigor expected in KDoc:
 * - WHY it exists in the product or architecture
 * - HOW it fits into the larger workflow
 * - Side effects (DB writes, HTTP calls, SQS messages, file I/O)
 * - Important invariants, nullability, retry behavior, and failure modes
 *
 * @param paramName Business meaning, valid values, and important constraints.
 * @returns What the caller receives, including empty/null/error semantics.
 * @throws Error When and why the function throws.
 * @see RelatedSymbol for nearby contracts or cross-layer dependencies.
 */
```

### Documentation Checklist (Applied to Every Change)

1. **Type/class/module docs**: Purpose, architectural role, key behaviors, configuration needs
2. **Function/method docs**: What it does, all params, return value, thrown exceptions, side effects
3. **Property/field docs**: Business meaning, valid values, constraints, nullability semantics
4. **Edge cases**: Document null handling, empty collections, boundary conditions
5. **Cross-references**: Use `@see` to link related classes (e.g., entity ↔ repository ↔ service)
6. **Concurrency**: Note if a method is suspend, blocking, or has thread-safety requirements
7. **Database impact**: Note if a method reads, writes, or modifies DB state
8. **SQS/async**: Note if a method enqueues messages or triggers async side effects
9. **External APIs**: Note outbound network calls, auth requirements, rate limits, and retry expectations
10. **Tests and fixtures**: Explain why the fixture exists, what contract it protects, and what failure would mean

### Anti-Patterns to Avoid

- **Parrot docs**: `/** Gets the name. */ fun getName()` — useless. Explain *what* name, *whose* name, *why* you need it.
- **Implementation details as docs**: Don't describe *how* the code works line-by-line. Describe *what* it achieves and *why*.
- **Missing exception docs**: If a function throws, document it. Callers need to know.
- **Stale docs**: The #1 violation. If you change a function's behavior, **update the KDoc in the same commit**. Period.
- **Undocumented TS helpers**: Internal TypeScript helpers are not exempt just because they are not exported.
- **Comment-free tests**: If a test protects a business rule or integration contract, document that rule.

### TypeScript Documentation

All TypeScript across the monorepo follows the same standard:
- Every interface and its fields must have doc comments
- Every exported function and every non-trivial internal helper must have doc comments
- Explain business meaning, not just type information
- Note which backend DTO, queue contract, or workflow the type corresponds to
- Add concise inline comments only where the control flow or data transformation would otherwise be hard to follow

### SQL Migration Documentation

Each migration file must include:
- A header comment explaining what the migration does and why
- Inline comments for non-obvious constraints, indexes, or design decisions
- Reference to the relevant design doc (e.g., `-- See docs/specs/phase-1/01-schema.md`)
