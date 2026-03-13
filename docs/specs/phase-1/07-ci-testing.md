# FieldIQ -- Phase 1 CI & Testing

---

## GitHub Actions CI

### `.github/workflows/backend-ci.yml`

```yaml
name: Backend CI
on:
  push:
    paths: ['backend/**']
  pull_request:
    paths: ['backend/**']

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16
        env:
          POSTGRES_DB: fieldiq_test
          POSTGRES_USER: fieldiq
          POSTGRES_PASSWORD: testpass
        ports: ['5432:5432']
        options: --health-cmd pg_isready --health-interval 5s
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - name: Run tests
        working-directory: backend
        run: ./gradlew test
        env:
          DATABASE_URL: jdbc:postgresql://localhost:5432/fieldiq_test
          DATABASE_USERNAME: fieldiq
          DATABASE_PASSWORD: testpass
          JWT_SECRET: test-secret
          FIELDIQ_INSTANCE_SECRET: test-instance-secret
          # Redis and SQS beans are disabled/mocked in test profile
          SPRING_PROFILES_ACTIVE: test
```

### `.github/workflows/agent-ci.yml`

```yaml
name: Agent CI
on:
  push:
    paths: ['agent/**']
  pull_request:
    paths: ['agent/**']

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20' }
      - name: Install and test
        working-directory: agent
        run: |
          npm ci
          npm test
        env:
          # All external services mocked in tests
          ANTHROPIC_API_KEY: test-key
          AWS_ENDPOINT_URL: http://localhost:4566
```

---

## CI Hardening Notes

- **Flyway migrations run on app startup** in test profile (Spring Boot default with `spring.flyway.enabled=true`). Tests verify that all migrations apply cleanly.
- **Redis beans** are conditionally loaded. Test profile sets `spring.data.redis.enabled=false` or uses an embedded Redis alternative. Tests that need Redis use `@ConditionalOnProperty`.
- **SQS beans** are mocked in test profile. `SqsClient` is replaced with a no-op or in-memory stub.
- **Agent tests** mock the Anthropic API client and SQS. No external network calls in CI.
- **Dependency review** runs on pull requests to catch risky dependency changes before merge.
- **CodeQL** scans Java/Kotlin and JavaScript/TypeScript on pull requests and on `main`.
- **Dependabot** keeps GitHub Actions, Gradle, and npm dependencies moving on a weekly cadence.

---

## Testing Strategy

```
Backend unit tests     -> JUnit 5 + MockK -- all Service layer methods
                          Minimum coverage: SchedulingService, NegotiationService,
                          TeamAccessGuard, auth flow

Backend integration    -> TestContainers (real Postgres in CI, not H2)
                          Flyway migrations applied on startup
                          Redis and SQS mocked

Negotiation protocol   -> Integration test: two NegotiationService instances
                          wired to different DataSources, full round-trip
                          in a single test class (in-process, no HTTP)
                          Tests: happy path, max rounds exceeded, cancel,
                          idempotent duplicate proposal, expired session,
                          invalid state transition, HMAC signature rejection

Agent layer            -> Jest -- mock Anthropic API, mock SQS, mock DB
                          Tests: calendar sync transforms busy blocks correctly,
                          CommunicationAgent produces valid messages

Mobile                 -> Manual testing Sprints 5-6, Detox E2E Sprint 7+

North star test (Sprint 7):
  Given: Team A and Team B both on FieldIQ with calendar sync
  When:  Team A manager initiates negotiation from the mobile app
  Then:  Within 60 seconds, both managers receive a push notification
         with a proposed game time that fits both teams' availability
         AND confirming creates a FieldIQ event on both teams
         AND both managers receive a confirmation with .ics download link
```
