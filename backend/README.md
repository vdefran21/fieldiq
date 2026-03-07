# FieldIQ Backend

Kotlin Spring Boot API server. Owns authentication, team/event CRUD, scheduling logic, the cross-team negotiation protocol, and all REST/WebSocket endpoints.

For architecture decisions and coding standards, see [CLAUDE.md](../CLAUDE.md). For detailed design, see [docs/](../docs/).

## Prerequisites

- Java 21 (Temurin recommended)
- Docker & Docker Compose (for Postgres, Redis, LocalStack)

## Running

Infrastructure must be up first:

```bash
# From repo root
docker compose up -d
```

Then run the backend with a Spring profile:

```bash
# Instance A — port 8080, database: fieldiq on localhost:5432
SPRING_PROFILES_ACTIVE=instance-a ./gradlew bootRun

# Instance B — port 8081, database: fieldiq_team_b on localhost:5433
SPRING_PROFILES_ACTIVE=instance-b ./gradlew bootRun
```

Or use the orchestration script from the repo root:

```bash
./dev.sh          # starts infra + both backends (Instance A and B)
./dev.sh a        # starts infra + Instance A only
./dev.sh b        # starts infra + Instance B only
./dev.sh infra    # starts infrastructure only (Postgres, Redis, LocalStack)
./dev.sh stop     # stops Docker containers
./dev.sh stop --all  # stops containers + removes images and volumes
```

## Spring Profiles

| Profile | Port | Database | Instance ID | Use |
|---------|------|----------|-------------|-----|
| `instance-a` | 8080 | `fieldiq` on :5432 | `instance-a` | Default local dev |
| `instance-b` | 8081 | `fieldiq_team_b` on :5433 | `instance-b` | Cross-team negotiation testing |
| `test` | — | `fieldiq_test` on :5432 | `test-instance` | Unit/integration tests (auto-selected by TestContainers) |

## Testing

### Unit Tests

Uses JUnit 5, MockK, and TestContainers (spins up a real Postgres container — no H2).

```bash
./gradlew test
```

Run `./gradlew test` to see current counts.

### Bruno API Integration Tests

[Bruno](https://www.usebruno.com/) collections live in `backend/bruno/`. They test the live API contract against a running Instance A.

```bash
# Requires Instance A running on port 8080
cd bruno && npm test

# Or target a specific instance
npm run test:instance-a
npm run test:instance-b
```

Results are written to `bruno/results/output.html` and `bruno/results/output.json`.

Collections cover auth, teams, events, availability, and scheduling.

## Configuration

Base config is in `src/main/resources/application.yml`. Instance profiles override DB URL, port, and instance ID.

Key config properties under `fieldiq.*`:

| Property | Purpose | Default |
|----------|---------|---------|
| `fieldiq.instance.id` | Identifies this instance in cross-team relay | `local-dev` |
| `fieldiq.instance.secret` | HMAC key derivation secret | dev default |
| `fieldiq.instance.base-url` | This instance's public URL for relay callbacks | `http://localhost:8080` |
| `fieldiq.jwt.secret` | HS256 signing key for access/refresh tokens | dev default |
| `fieldiq.jwt.expiration-ms` | Access token TTL in milliseconds | `900000` (15 min) |
| `fieldiq.jwt.refresh-expiration-ms` | Refresh token TTL in milliseconds | `2592000000` (30 days) |
| `fieldiq.google.client-id` | Google OAuth client ID | env var |
| `fieldiq.google.client-secret` | Google OAuth client secret | env var |
| `fieldiq.google.redirect-uri` | OAuth callback URL | `http://localhost:8080/auth/google/callback` |
| `fieldiq.encryption.token-key` | AES-256-GCM key for OAuth token encryption (32 chars min) | dev default |
| `fieldiq.aws.endpoint-url` | AWS endpoint (LocalStack in dev) | `http://localhost:4566` |
| `fieldiq.aws.region` | AWS region | `us-east-1` |
| `fieldiq.aws.sqs.agent-tasks-queue` | SQS queue URL for agent tasks | LocalStack default |
| `fieldiq.aws.sqs.notifications-queue` | SQS queue URL for notifications | LocalStack default |
| `fieldiq.aws.sqs.negotiation-queue` | SQS queue URL for negotiation events | LocalStack default |

## Database

Schema is managed by Flyway. Migrations run automatically on startup.

```
src/main/resources/db/migration/
├── V1__initial_schema.sql              # Core tables (teams, users, events, etc.)
├── V2__negotiation_schema.sql          # Negotiation protocol tables
├── V3__rate_limiting.sql               # OTP rate limiting
├── V4__drop_auth_token_hash_unique.sql # Auth token constraint fix
└── V5__auth_token_identifier_binding.sql # Token-identifier binding
```

Hibernate is set to `validate` only — it will fail on startup if the schema doesn't match entities, but it won't modify the schema. All changes go through Flyway.

## Package Structure

```
src/main/kotlin/com/fieldiq/
├── FieldIQApplication.kt    # Entry point, UTC timezone init
├── api/                     # REST controllers (Auth, Team, Event, Scheduling, GoogleCalendar)
│   └── dto/                 # Request/response DTOs
├── config/                  # Spring config (FieldIQProperties, AppConfig, RedisConfig)
├── domain/                  # JPA entities
├── repository/              # Spring Data JPA repositories
├── security/                # JWT filter, HMAC service/filter, TokenEncryptionConverter, SecurityConfig
├── service/                 # Business logic (TeamAccessGuard, SchedulingService, GoogleCalendarService, etc.)
└── websocket/               # Real-time negotiation updates (Sprint 6)
```

## Troubleshooting

**`bootRun` fails with "relation does not exist"**
Flyway hasn't run or the profile is pointing to the wrong database. Check `SPRING_PROFILES_ACTIVE` and that Docker is up (`docker compose ps`).

**TestContainers tests fail with Docker errors**
TestContainers needs Docker running. On macOS, ensure Docker Desktop is started.

**Bruno tests return connection refused**
Instance A must be running on port 8080 before running Bruno tests. Bruno tests are not self-contained — they hit the live API.

**Port already in use**
`./dev.sh stop --all` cleans up stale Java processes. Or check `lsof -i :8080`.

**Jackson serialization differs between test and runtime**
The app uses `NON_NULL` serialization (null fields omitted). Standalone `ObjectMapper()` in tests includes nulls by default. Use Spring's configured mapper or set `setSerializationInclusion(JsonInclude.Include.NON_NULL)` in tests.
