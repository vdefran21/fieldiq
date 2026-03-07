# FieldIQ

AI-powered youth sports management platform. The core differentiator is a **cross-team scheduling negotiation protocol** — two FieldIQ instances autonomously negotiate game times between teams, eliminating the back-and-forth text chains that plague youth sports scheduling.

**Phase 1 target:** Working cross-team scheduling negotiation demo + iOS MVP for DMV youth soccer.

## Quick Start

```bash
# 1. Start infrastructure (Postgres x2, Redis, LocalStack)
docker compose up -d

# 2. Run backend Instance A (port 8080)
cd backend
SPRING_PROFILES_ACTIVE=instance-a ./gradlew bootRun

# 3. (Optional) Run Instance B for cross-team testing (port 8081)
SPRING_PROFILES_ACTIVE=instance-b ./gradlew bootRun
```

## Architecture

```
┌─────────────┐     REST/WS     ┌──────────────────┐     SQS      ┌─────────────┐
│  Mobile App  │ ──────────────> │  Kotlin Backend   │ ──────────> │ Agent Layer  │
│  (iOS/Expo)  │ <────────────── │  (Spring Boot)    │             │ (Node.js/TS) │
└─────────────┘                  └──────────────────┘             └─────────────┘
                                   │            │                       │
                                   │    HMAC    │                       │
                                   │  HTTP ↕↕   │                       │
                                   │            │                       │
                                 ┌──────────────────┐             ┌───────────┐
                                 │  Remote FieldIQ   │             │  Claude   │
                                 │  Instance (Team B) │             │  Haiku    │
                                 └──────────────────┘             └───────────┘
                                        │
                                   ┌─────────┐
                                   │Postgres │  Redis  │  LocalStack (SQS)
                                   └─────────┘
```

- **[Backend](backend/) (Kotlin Spring Boot):** Owns all scheduling logic, negotiation protocol, auth, and REST/WebSocket APIs.
- **[Agent Layer](agent/) (Node.js/TypeScript):** Async SQS workers for calendar sync, LLM message drafting (Claude Haiku), and notification dispatch. Does NOT own scheduling.
- **Mobile (React Native Expo):** iOS app with Expo Router. Schedule feed, team roster, negotiation approval screen.

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Backend | Kotlin, Spring Boot 3.3, Java 21 | API server, scheduling, negotiation |
| Database | PostgreSQL 16, Flyway | Schema management, data persistence |
| Cache | Redis 7 | Sessions, rate limiting |
| Queue | AWS SQS (LocalStack in dev) | Backend → Agent async communication |
| Agent | Node.js 20, TypeScript | Calendar sync, LLM drafting, notifications |
| Mobile | React Native, Expo, Expo Router | iOS app (Phase 1) |
| AI | Claude Haiku | Message drafting for reminders/notifications |

## Project Structure

```
fieldiq/
├── backend/                 Kotlin Spring Boot API (see backend/README.md)
├── agent/                   Node.js/TypeScript SQS workers (see agent/README.md)
├── mobile/                  React Native Expo app (iOS)
├── shared/types/            TypeScript API contract interfaces
├── docs/                    Phase 1 implementation plans (00-08) + tracking
├── infra/                   LocalStack init scripts
├── docker-compose.yml       Full local dev environment
└── CLAUDE.md                Architecture decisions & coding standards
```

The backend and agent layers have their own READMEs with setup, configuration, testing, and troubleshooting details.

## Cross-Team Negotiation Protocol

The core IP. Two FieldIQ instances negotiate a game time autonomously:

1. **Manager A** initiates negotiation, generating an invite token
2. **Manager B** joins via invite token; HMAC session key derived
3. Both instances exchange time slot proposals (up to 3 rounds)
4. On match, both managers confirm via mobile app
5. Game events created on both teams, notifications sent

All cross-instance communication is authenticated via HMAC-SHA256 signatures.

## Development

### Prerequisites

- Java 21 (Temurin recommended)
- Docker & Docker Compose
- Node.js 20+ (for agent layer)

### Environment Setup

```bash
cp .env.example .env.local
# Edit .env.local with your API keys (Anthropic, Google OAuth, Twilio)
```

### Running Tests

```bash
# Backend (uses TestContainers with real Postgres)
cd backend && ./gradlew test

# Agent layer
cd agent && npm ci && npm test
```

### Two-Instance Local Dev

For testing the cross-team negotiation protocol:

```bash
# Terminal 1 — Instance A (port 8080, database: fieldiq on :5432)
SPRING_PROFILES_ACTIVE=instance-a ./gradlew bootRun

# Terminal 2 — Instance B (port 8081, database: fieldiq_team_b on :5433)
SPRING_PROFILES_ACTIVE=instance-b ./gradlew bootRun
```

## Documentation

| Document | Purpose |
|----------|---------|
| [CLAUDE.md](CLAUDE.md) | Architecture decisions, coding standards, KDoc requirements |
| [backend/README.md](backend/README.md) | Backend setup, profiles, config, testing, troubleshooting |
| [agent/README.md](agent/README.md) | Agent setup, env vars, worker responsibilities, troubleshooting |
| [docs/](docs/) | Phase 1 implementation plans (00–08: schema, auth, backend, negotiation, agent, mobile, CI, architecture) |
| [docs/IMPLEMENTATION_TRACKING.md](docs/IMPLEMENTATION_TRACKING.md) | Sprint progress tracker |

## License

Proprietary. All rights reserved.
