# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FieldIQ is an AI-powered youth sports management platform. The core differentiator is a **cross-team scheduling negotiation protocol** — two FieldIQ instances autonomously negotiate game times between teams on different instances.

Phase 1 target: working cross-team scheduling negotiation demo + iOS MVP for DMV youth soccer.

## Tech Stack

- **Backend:** Kotlin Spring Boot (Java 21) — owns all scheduling and negotiation logic
- **Agent Layer:** Node.js/TypeScript — async SQS workers for calendar sync, LLM message drafting (Claude Haiku), notification dispatch
- **Mobile:** React Native (Expo) with Expo Router — iOS only in Phase 1
- **Database:** PostgreSQL 16 (Flyway migrations)
- **Infrastructure:** Redis (cache/sessions), AWS SQS via LocalStack (async tasks), WebSocket (real-time negotiation updates)
- **Shared types:** TypeScript interfaces in `shared/types/` — Kotlin DTOs maintained manually to match (no codegen)

## Repository Structure

```
backend/          Kotlin Spring Boot API (scheduling, negotiation, auth, REST, WebSocket)
agent/            Node.js/TypeScript SQS workers + Claude Haiku integration
mobile/           React Native Expo app (iOS)
shared/types/     TypeScript API contract interfaces
docs/             Phase 1 implementation plans (00-07)
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

## Important Conventions

- When changing API response shapes, update both the Kotlin DTO and the corresponding TypeScript interface in `shared/types/` in the same commit
- No child PII in `users` table (COPPA). Only parent/coach data. Child names only in `team_members.player_name`
- Dev OTP bypass: `+1555*` phone numbers are exempt from rate limiting
- WebSocket is server-to-client push only in Phase 1 — all mutations go through REST

---

## Documentation Standards (KDoc)

> **THIS IS NOT OPTIONAL.** Every code change — new code, refactored code, changed signatures,
> renamed parameters, modified behavior — MUST include corresponding KDoc updates in the same
> commit. Stale documentation is worse than no documentation. If you change how something works,
> update the KDoc to reflect the current state before committing.

### What Must Be Documented

**Every** public and internal class, function, method, and property must have KDoc. No exceptions.
This includes:

- Domain entities (explain what the table represents, relationships, constraints)
- Repository interfaces (explain query semantics, what "find by X" actually returns)
- Service classes (explain business logic, orchestration role, side effects)
- Controller endpoints (explain request/response contract, auth requirements)
- Configuration classes (explain what they configure and why)
- Data classes / DTOs (explain what each field means in business terms)
- Extension functions and utilities

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

### Documentation Checklist (Applied to Every Change)

1. **Class-level KDoc**: Purpose, architectural role, key behaviors, configuration needs
2. **Function-level KDoc**: What it does, all params, return value, thrown exceptions, side effects
3. **Property-level KDoc**: Business meaning, valid values, constraints, nullability semantics
4. **Edge cases**: Document null handling, empty collections, boundary conditions
5. **Cross-references**: Use `@see` to link related classes (e.g., entity ↔ repository ↔ service)
6. **Concurrency**: Note if a method is suspend, blocking, or has thread-safety requirements
7. **Database impact**: Note if a method reads, writes, or modifies DB state
8. **SQS/async**: Note if a method enqueues messages or triggers async side effects

### Anti-Patterns to Avoid

- **Parrot docs**: `/** Gets the name. */ fun getName()` — useless. Explain *what* name, *whose* name, *why* you need it.
- **Implementation details as docs**: Don't describe *how* the code works line-by-line. Describe *what* it achieves and *why*.
- **Missing exception docs**: If a function throws, document it. Callers need to know.
- **Stale docs**: The #1 violation. If you change a function's behavior, **update the KDoc in the same commit**. Period.

### TypeScript Documentation (Shared Types)

The `shared/types/index.ts` file uses JSDoc-style comments following the same principles:
- Every interface and its fields must have doc comments
- Explain business meaning, not just type information
- Note which Kotlin DTO each interface corresponds to

### SQL Migration Documentation

Each migration file must include:
- A header comment explaining what the migration does and why
- Inline comments for non-obvious constraints, indexes, or design decisions
- Reference to the relevant design doc (e.g., `-- See docs/01_Phase1_Schema.md`)
